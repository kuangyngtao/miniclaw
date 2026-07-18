# P0-R 底层结构重构收尾实施方案与评审

> 版本：v1.0
> 日期：2026-07-13
> 用途：交给 Claude + DeepSeek 按 PR 顺序实施
> 依据：`CLAUDE.md`、`DESIGN.md`、`TODO.md`、当前工作区代码与 `docs/p0-r-implementation-plan-review.md`

## 1. 结论与范围

P0-R 当前不是“推倒重做”，而是“主链已迁移，边界尚未收口”。本方案只处理已存在但未完成的重构，不新增产品能力。

完成后必须同时满足：

1. Provider 调用只能经过 `ProviderGateway`，不存在 raw provider fallback。
2. 模型上下文和 compact 只能经过 `ContextPipeline`，不存在 engine fallback。
3. Gateway、Agent、Plan、SubAgent 共用同一个可组合 `RunRecorder`，Provider 终态事件恰好一次。
4. Session 的真实 load/save/list/delete 经过 `SessionStore`，旧 v0/v1 文件兼容读取。
5. AgentEngine 只通过 `MemoryHooks`、`SkillRuntime` 使用记忆和 Skill，不直接维护具体实现状态。
6. REPL 真实使用 `SlashCommandRouter` 和 `ApprovalConsole`；查询类命令不调用模型。
7. ArchUnit 不使用冻结基线掩盖 Provider/Context 违规，全部规则以普通硬规则零违规通过。

明确不在本轮做：Provider 多模型 fallback、远程 Session、数据库存储、异步记忆队列、Web UI、插件系统重写、全面重写 AgentEngine 状态机。

## 2. 当前未完成事实

| 编号 | 当前事实 | 影响 |
| --- | --- | --- |
| G1 | `ApplicationBootstrap` 给 `ObservingProviderGateway` 传入空 lambda | Provider 事件未进入 recorder |
| G2 | `AgentEngine.fallbackGenerate()`、`SessionService.generateSummary()`、CLI `/remember` 仍直调 provider | Gateway 不是唯一入口 |
| C1 | `compactSession()` 仍有 `ContextManager.compact()` fallback | Pipeline 不是唯一入口 |
| C2 | `fallbackModelContext()`、nullable setter、重复 `initContextPipeline()` 仍存在 | Context 依赖不变量不清晰 |
| S1 | `FileSessionStore` 未实现 `SessionStore`，新旧 `load` 返回类型冲突 | Session 类型体系未进入生产 |
| S2 | AgentEngine 仍用裸 `List<Message>` 保存会话 | ephemeral/persisted 边界仍靠调用方自觉 |
| M1 | `MemoryHooks`、`SkillRuntime` 只有接口 | AgentEngine 仍直接操作 memory/skill 状态 |
| R1 | `SlashCommandRouter` 只能匹配无参数命令且未接入 REPL | CLI 仍由巨型 switch 驱动 |
| R2 | `ApprovalConsole` 已创建但生产仍调用旧 `handleApproval()` | 审批 UI 有两套路径 |
| A1 | ArchUnit Provider/Context 规则仍为 `FreezingArchRule` | 测试绿色不等于零违规 |
| O1 | Plan 只取得 `recorders.get(0)` | 多 recorder 时事件丢失 |

## 3. 目标架构

```text
ApplicationBootstrap
  ├─ CompositeRunRecorder
  │    ├─ FileRunRecorder
  │    └─ optional test/evaluation recorders
  ├─ ObservingProviderGateway(raw provider, CompositeRunRecorder)
  ├─ FileSessionStore : SessionStore
  ├─ DefaultMemoryHooks
  ├─ DefaultSkillRuntime
  └─ AgentRuntimeDependencies
         ├─ ProviderGateway
         ├─ ContextPipelineFactory
         ├─ SessionHistory / SessionStore
         ├─ MemoryHooks / SkillRuntime
         ├─ RunRecorder
         └─ Registry / context configuration

ReplLoop -> SlashCommandRouter -> command handlers -> application services
         -> non-command input -> AgentEngine

AgentEngine
  -> ContextPipeline
  -> ProviderGateway
  -> ToolCallExecutor
  -> SessionHistory / SessionService
  -> MemoryHooks / SkillRuntime
  -> RunRecorder
```

约束：`clawkit-provider`、`clawkit-context`、`clawkit-memory`、`clawkit-observability` 不得反向依赖 engine；CLI 是组合根，但不得承载 Agent 核心业务规则。

## 4. 实施顺序总览

严格按 PR-9 至 PR-15 顺序执行。前一 PR 未满足验收标准，不得开始后一 PR 的大规模迁移。

| PR | 主题 | 主要清零项 |
| --- | --- | --- |
| PR-9 | Recorder 与 Gateway 观测接线 | G1、O1 |
| PR-10 | ProviderGateway 唯一入口 | G2、Provider frozen violations |
| PR-11 | ContextPipeline 唯一入口 | C1、C2、Context frozen violation |
| PR-12 | SessionStore / SessionHistory 生产迁移 | S1、S2 |
| PR-13 | MemoryHooks / SkillRuntime 生产接入 | M1 |
| PR-14 | REPL / Router / ApprovalConsole 接入 | R1、R2 |
| PR-15 | 删除兼容旁路、硬化架构门禁、文档验收 | A1、全部残余 |

---

## 5. PR-9：Recorder 与 Gateway 观测接线

### 5.1 目标

让 Engine、ProviderGateway、Plan、SubAgent 共用同一 recorder，消除空 sink 和“只取首个 recorder”。

### 5.2 代码改动

1. 在 `clawkit-observability` 新增 `CompositeRunRecorder implements RunRecorder`。
   - 内部使用 `CopyOnWriteArrayList<RunRecorder>`。
   - 支持构造时传入 delegates，也支持 `add/remove`。
   - 单个 delegate 写失败时记录日志并继续其他 delegate，不能让观测失败中断 Agent run。
   - 禁止把 composite 自己加入自己；同一实例重复加入应幂等。
2. 修改 `ObservingProviderGateway`：
   - 构造参数改为 `LLMProvider + RunRecorder`，不再接收 `Consumer<ProviderEvent>`。
   - 直接把 `ProviderCallStartedPayload/CompletedPayload` 写入 recorder。
   - 使用 `RunScope` 写入 runId、parentRunId、turn、phase。
   - 非流式成功/失败各产生一个终态。
   - 流式使用 `AtomicBoolean terminalSent`；无论 provider 通过 callback 终止、方法正常返回还是抛异常，Provider 终态事件都必须恰好一次。
3. 修改 `AgentRuntimeDependencies`，增加非空 `RunRecorder runRecorder`。
   - 生产传 `CompositeRunRecorder`。
   - 兼容构造器传 `NoopRunRecorder` 或空 composite。
4. `AgentEngine` 只保存一个共享 recorder。
   - `fireEvent()` 写该 recorder。
   - `PlanExecutionContext` 传该 recorder，不再 `recorders.get(0)`。
   - SubAgent 继承同一 recorder，同时使用 child `RunScope` 区分父子 run。
   - `addRecorder()` 保留为兼容 API，但实现为向 composite 添加 delegate；不得再维护第二套列表。
5. `ApplicationBootstrap` 先创建 recorder，再创建 gateway 和 engine。
   - 删除空 lambda。
   - 删除重复 `engine.setProviderGateway(gateway)`。
   - 删除 `AgentEngine.fireProviderEvent()` 和自定义 `ProviderEvent` 桥接层。

### 5.3 测试

- `CompositeRunRecorderTest`
  - 两个 delegate 都收到同一事件。
  - 一个 delegate 抛异常时另一个仍收到事件。
  - 重复 add 不重复记录。
- `ObservingProviderGatewayTest`
  - 非流式 success/failure。
  - 流式 callback complete、callback error、直接 throw、正常 return 未 callback。
  - 每种路径恰好一个 started 和一个 terminal。
  - parentRunId/turn/phase 正确。
- `PlanExecutorTest`：两个 recorder delegate 都收到 worker/reviewer 事件。
- `SubAgentTest`：父子 runId 不混写，parentRunId 正确。

### 5.4 验收

- `rg "wired below|fireProviderEvent|recorders.get\(0\)"` 在 main 源码中无结果。
- Provider 调用能在 `events.jsonl` 中看到 started + exactly-one terminal。
- recorder 故障不改变 Agent 最终结果。

### 5.5 回滚

只回滚 recorder 装配，不回滚已存在的 Provider DTO。保留 `RunRecorder` 接口不变，因此文件格式无迁移。

---

## 6. PR-10：ProviderGateway 唯一入口

### 6.1 目标

所有生产和兼容路径都经过 Gateway；旧构造器可以保留，但只能是薄适配器，不能拥有 raw generate fallback。

### 6.2 代码改动

1. 删除 `AgentEngine.fallbackGenerate()` 和 `providerGateway != null ? ... : ...` 分支。
2. 旧 `AgentEngine(LLMProvider, ...)` 构造器内部立即创建：
   `new ObservingProviderGateway(provider, NoopRunRecorder)`，然后委托新构造器。
   - 旧构造器保持 `@Deprecated`，只用于源兼容。
   - AgentEngine 不再保存可调用的 raw provider 字段。
3. `SessionService` 改为依赖 `SessionStore + ProviderGateway`。
   - 摘要调用使用 `RunPhase.SESSION_SUMMARY`；在 `RunPhase` 新增该值。
   - `save` 接收调用方 `RunScope`，或接收明确的 `SummaryRequest(scope, messages)`，不得在 service 内伪造父子关系。
   - 模型失败仍使用现有 deterministic fallback summary，但失败必须由 gateway 记录。
4. `ApplicationContext` 暴露 `ProviderGateway`，CLI 不再持有可调用的 `LLMProvider`。
   - raw provider 只允许留在 `ApplicationBootstrap` 用于创建 gateway、读取静态 capabilities/config。
5. `/remember` 元数据提取改走 gateway，phase 使用 `MEMORY_EXTRACT`。
   - CLI 独立操作使用新的 root scope，例如 `cli-<uuid>`；不可复用上一次 Agent runId。
6. Plan、SubAgent、compact summarizer、memory extraction、session summary 全部检查并迁移。
7. ArchUnit Provider 规则扩大到：
   - `LLMProvider.generate(ModelRequest)`
   - legacy `generate(List,List)`
   - `generateStream(...)`
   - 唯一允许类为 `ObservingProviderGateway`。

### 6.3 测试

- 把 `AgentEngineTest` 等旧测试逐步改为注入 fake `ProviderGateway`。
- 额外保留一个兼容构造器测试，证明旧构造器也经过 gateway adapter 且 tool calls 不丢失。
- `SessionServiceTest`：摘要成功、gateway 失败 fallback、scope 正确。
- CLI `/remember`：fake gateway 返回合法 JSON、坏 JSON、异常；不访问网络。
- ArchUnit Provider 规则先以普通规则运行并达到 0，再删除冻结文件。

### 6.4 验收

- main 源码中除 `ObservingProviderGateway` 外不存在任何 `LLMProvider.generate*()` 调用。
- `AgentEngine` 不存在 `fallbackGenerate`，也不存在 nullable gateway 分支。
- Provider 冻结存储文件为空并删除对应 freeze 配置。

### 6.5 回滚

旧构造器薄适配保留一个发布周期；如新调用点出现问题，回滚调用点，但不得恢复 raw fallback，统一在 gateway adapter 内修复。

---

## 7. PR-11：ContextPipeline 唯一入口

### 7.1 目标

所有执行模式只从 ContextPipeline 获取模型消息，所有 compact 只调用 `ContextPipeline.compact()`。

### 7.2 代码改动

1. 新增 `ContextPipelineFactory`（放 `clawkit-context`，方法为
   `ContextPipeline create(Summarizer summarizer)`），并让 `AgentRuntimeDependencies`
   注入非空 factory，而不是共享一个具体 pipeline 实例。
   - AgentEngine 构造时调用 factory，并绑定自己的 `summarizeForCompact`。
   - SubAgent 继承 factory，但创建自己的 pipeline；禁止直接共享父 Agent 的
     `DefaultContextPipeline`，因为其中的 `LadderedCompactor` 当前绑定父 Agent 的 summarizer。
   - 测试可注入返回 fake pipeline 的 factory。
2. 保证 `AgentEngine.contextPipeline` 构造完成后永久非空。
   - 新旧构造器都委托同一初始化路径。
   - 删除允许传 null 的 setter、`initContextPipeline()` 和 Bootstrap 的重复初始化。
3. 删除 `fallbackModelContext()` 及全部 `contextPipeline != null` 分支。
4. 删除 `compactSession()` 中 `contextManager.compact()` fallback。
5. 修正 compact 指标：
   - 在替换消息前保存 beforeMessages/beforeTokens/beforeSections。
   - 返回 afterMessages/afterTokens/afterSections、compacted、duration。
   - `MessageMasker` 的 evicted groups 和 reduce/appliedRules 进入结果或事件。
   - no-op compact、普通 compact、HARD_LIMIT 三条路径分别测试。
6. 稳定 system prompt 只由 `ContextRequest.systemPrompt` 注入。
   - SessionHistory 不持久化稳定 system prompt。
   - `[Conversation Summary]`、`[Preserved Constraints]` 等 compact 产物作为 session facts 保留。
   - 使用语义标记/稳定前缀识别，不再按 `Role.SYSTEM` 全量删除。

### 7.3 测试

- `DefaultContextPipelineTest`：片段顺序、生命周期、预算、mask、三种 compact 结果。
- `AgentEngineTest`：ReAct、TWO_STAGE、Plan、SubAgent 每次 provider 请求都来自 pipeline。
- 多轮对话：稳定 system prompt 恰好一次，compact summary 不丢失。
- `/compact`：before/after message 数和 token 数真实变化；no-op 不伪报 compacted。
- ArchUnit Context 规则改普通规则并清零。

### 7.4 验收

- main 源码除 `DefaultContextPipeline` 外无 `ContextManager.compact()` 调用。
- 无 `fallbackModelContext`、`initContextPipeline`、nullable pipeline 分支。
- Context 冻结文件删除，普通 ArchRule 通过。

### 7.5 回滚

回滚时只能回到前一个 Pipeline 实现版本，不能恢复 engine 旁路。消息顺序变化必须通过测试快照定位，而不是双写两套上下文。

---

## 8. PR-12：SessionStore 与 SessionHistory 生产迁移

### 8.1 目标

解决 `FileSessionStore` 新旧签名冲突，建立原子、版本化、结构化错误的真实持久化边界。

### 8.2 契约决策

- `SessionStore.load(String)` 保持 `Optional<SessionDocument>`：不存在返回 empty。
- 损坏、未知版本、IO 错误使用新增 `SessionStoreException`，包含 `SessionError code` 和 cause。
- 禁止 `exists()` 吞掉损坏/IO 错误；只把 NOT_FOUND 视为 false。修改当前 default 实现。
- 当前 schema 继续为 v1；不为了接口重构升级磁盘 schema。

### 8.3 代码改动

1. `FileSessionStore implements SessionStore`。
2. 解决返回类型冲突：
   - 旧 `load(String): List<Message>` 重命名为 `loadMessagesLegacy` 并 `@Deprecated`，随后迁移调用点并在 PR-15 删除。
   - 旧多参数 `save` 作为私有转换 helper 或 deprecated adapter。
3. 读取兼容：
   - 无 `schemaVersion` 的 v0 文件转换为 `SessionDocument`。
   - 当前 v1 `{meta,messages}` 文件转换为 `SessionDocument`。
   - 高于支持版本抛 `UNSUPPORTED_VERSION`。
   - JSON 损坏抛 `CORRUPTED_JSON`，不能返回空会话。
   - index 是派生数据；index 缺失或损坏时从 session 文件重建，不能静默重置为空后覆盖索引。
4. 写入可靠性：
   - session 文件和 index 都先写同目录临时文件，再 `ATOMIC_MOVE + REPLACE_EXISTING`；不支持原子移动时退化为 `REPLACE_EXISTING` 并记录 warning。
   - 用 store 级 lock 串行化“写文档 + 更新 index”。
   - sessionId 限制为 `[A-Za-z0-9_-]{1,64}`，继续做 normalized path root 校验。
5. `SessionService` 字段类型改为 `SessionStore`，构造器注入接口。
6. `SessionDocument`、`SessionSnapshot` 构造时对 List/Map 做防御性复制，时间字段和 metadata 给出明确非空规则。
7. `AgentEngine` 把裸 `List<Message>` 改为 `SessionHistory`。
   - append/clear/restore 全部使用接口。
   - 传给 pipeline 和 provider 时使用 immutable `messages()`。
   - 保存前再执行 persistable filter，作为纵深防御。
   - 加载使用 snapshot/restore，不暴露内部 mutable list。
8. Session 只保存用户、助手、工具调用/结果和 compact 事实；runtime、workspace、memory、skill、稳定 system prompt 不落盘。

### 8.4 测试

- `FileSessionStoreTest`：v0 读取、v1 读取/写入、未知版本、坏 JSON、not found、路径穿越、原子替换、并发 save/index 一致性。
- `SessionServiceTest`：只依赖 fake SessionStore + fake gateway。
- `InMemorySessionHistoryTest`：snapshot 不可变、restore、外部 list 不能修改内部状态。
- AgentEngine save/load 集成：ephemeral 不落盘，compact summary 保留。

### 8.5 验收

- `SessionService` 不 import `FileSessionStore`。
- AgentEngine 不声明 `List<Message> sessionHistory`。
- 旧 v0/v1 fixture 均可读取，坏文件不会被当作空会话覆盖。

### 8.6 回滚

磁盘 schema 不升级，因此可回滚代码。实施中严禁批量改写用户已有 session；只在下一次显式 save 时写当前格式。

---

## 9. PR-13：MemoryHooks 与 SkillRuntime 生产接入

### 9.1 目标

AgentEngine 不再直接依赖 `DiskMemoryService`、`SkillLoader`、`activeSkills`，只使用稳定运行时端口。

### 9.2 代码改动

1. 新增 `DefaultMemoryHooks`（放 engine impl，避免 memory 反向依赖 engine）。
   - 依赖 memory store/service、ProviderGateway、RunRecorder。
   - `beforeRun` 每个 root run 最多执行一次，失败返回空且记录 warning/event，不修改 SessionHistory。
   - `afterRun` 仅在 run 成功且达到配置阈值时执行；第一版同步执行，避免异步任务在进程退出时丢失。
   - 模型提取使用 `RunPhase.MEMORY_EXTRACT`。
   - 保存前做名称规范化、去重、冲突判断；不得静默覆盖冲突记忆。
2. 保持 `MemoryHooks` 只负责生命周期。CLI 的 list/add/delete/remember 放入独立 `MemoryCommandService`：
   - CRUD 使用 `DiskMemoryService`。
   - `/remember` 的模型提取使用 ProviderGateway。
   - 查询/list/delete 不调用模型。
3. 新增 `DefaultSkillRuntime`（engine impl）。
   - 依赖 `SkillLoader`。
   - 内部用线程安全 map 保存 active skills。
   - `load/unload/rebuildCatalog/activeContext` 作为唯一状态入口。
   - skill 名称做白名单校验，SkillLoader 的最终路径必须仍位于 user/project skill root。
4. `AgentRuntimeDependencies` 增加非空 `MemoryHooks` 和 `SkillRuntime`；兼容路径使用 `NoopMemoryHooks`、`EmptySkillRuntime`。
5. AgentEngine 迁移：
   - run 开始调用 `beforeRun`，结果进入 ContextRequest.memoryContext。
   - run 成功结束调用 `afterRun`。
   - internal `skill_load/skill_unload` 和 CLI `/skill` 都调用同一 SkillRuntime。
   - 删除 `activeSkills`、`skillLoader`、`diskMemoryService` 字段和对应 setters。
   - system prompt/catalog 从 `skillRuntime.catalog()` 获取；激活提示从 `activeContext()` 进入 pipeline，不写 Session。

### 9.3 测试

- `DefaultMemoryHooksTest`：召回成功/失败、提取成功/失败、去重、冲突、失败不污染 session、每 run 一次。
- `MemoryCommandServiceTest`：list/delete 不调用 gateway；remember 调用一次 gateway；坏 JSON 使用明确 fallback 或错误。
- `DefaultSkillRuntimeTest`：catalog override、load/unload 幂等、未知 skill、路径校验、activeContext 不持久化。
- AgentEngine 集成：memory/skill 注入只存在于 ModelContext，保存后文件中不存在临时文本。

### 9.4 验收

- AgentEngine 不 import `DiskMemoryService` 或直接引用 `SkillLoader`。
- main 源码只有 DefaultSkillRuntime 修改 active skill map。
- CLI 和 internal tools 对 skill load/unload 行为一致。

### 9.5 回滚

保留 DiskMemoryService 的磁盘格式不变。回滚 runtime 接入时不得删除或批量重写 memory 文件。

---

## 10. PR-14：REPL、SlashCommandRouter 与 ApprovalConsole 接入

### 10.1 目标

让已创建的 CLI 组件进入真实生产路径，并把命令解析、处理、展示、审批从 `ClawkitApp` 中分离。

### 10.2 代码改动

1. 把 Router 从静态 exact-match 改为实例化解析器：

```java
record SlashCommand(String name, String args, String raw) {}
Optional<SlashCommand> parse(String input);
CommandResult dispatch(SlashCommand command);
```

2. alias 只作用于命令名；参数原样保留并 trim。必须正确解析：
   - `/metrics <runId>`、`/trace <runId>`
   - `/session save|load|list|delete|stats|prune ...`
   - `/memory ...`、`/remember ...`、`/skill ...`、`/mcp ...`
   - `/im-on <channel>`、`/im-off <channel>`
3. 新增小型 `SlashCommandHandler` 和 `CommandResult(handled, exitRequested)`；未知命令统一返回未识别结果。
4. 新增 `ReplLoop`：
   - 只管理 readLine、中断/EOF、command dispatch 和把普通输入交给 engine。
   - 不负责 Provider、MCP、Memory、Session 的创建。
5. 把命令业务移动到 `CliCommandHandlers` 或按领域拆成 Session/Memory/Skill/Run/Im handlers。
   - handler 只依赖 `ApplicationContext` 中的稳定服务。
   - 状态查询命令不得调用 LLM。
6. 生产启动改为 `new ApprovalConsole(reader::readLine).asHandler()`；删除 ClawkitApp 旧 `handleApproval()`。
7. `ClawkitApp` 最终只保留 picocli 参数、Bootstrap 调用、启动 ReplLoop/IM 生命周期入口。

### 10.3 测试

- Router 参数化表驱动测试：alias、空参数、带空格内容、未知命令、非 slash 输入。
- ApprovalConsole：approve、approve-all、reject、modify、EOF、非法输入。
- ReplLoop：普通输入、命令、exit、Ctrl-C、EOF；全部使用 fake engine/reader。
- 每个 command handler 使用 fake service，不创建真实 provider、HOME、MCP 或 IM 网络连接。
- 保留现有 CLI 输出关键断言，允许非契约性空格调整但不得改变错误语义。

### 10.4 验收

- 生产 REPL 不再调用 `ClawkitApp.dispatchCommand()` 或 `handleApproval()`。
- `SlashCommandRouter` 能解析命令名和参数。
- `/runs`、`/metrics`、`/trace`、`/memory list` 等查询在 fake gateway 上验证调用次数为 0。

### 10.5 回滚

Router 合入前先锁定测试。若某一 handler 有问题，只回滚该 handler 注册，不恢复整块巨型 switch。

---

## 11. PR-15：删除旁路、硬化门禁与最终验收

### 11.1 清理

- 删除已无调用的 setter、legacy helper、ProviderEvent 桥接、fallback、重复装配和过时注释。
- 旧公开构造器如需保留，只能是委托新构造器的薄适配，标记删除版本和迁移示例。
- 删除 `archunit_store` 中 Provider/Context 冻结记录；工具规则也改成普通规则，避免以后误用冻结状态。
- 修正 TODO 顶部“当前代码事实”，不得同时出现互相矛盾的旧状态和新状态。

### 11.2 架构门禁

至少保留/新增以下普通 ArchRule：

1. Registry.execute 仅 ToolCallExecutor 可调用。
2. LLMProvider.generate/generateStream 仅 ObservingProviderGateway 可调用。
3. ContextManager.compact 仅 DefaultContextPipeline 可调用。
4. CLI/IM 不得直接 new AgentEngine，ApplicationBootstrap 除外。
5. AgentEngine 不得依赖 `DiskMemoryService`、`SkillLoader`、`FileSessionStore` 具体类。
6. observability/context/provider/memory 不得反向依赖 engine/cli。

### 11.3 最终验证命令

```powershell
mvn clean compile
mvn test
mvn -pl clawkit-evaluation -am test
git diff --check
```

另执行静态核验：

```powershell
rg -n "fallbackGenerate|fallbackModelContext|initContextPipeline|fireProviderEvent|recorders\.get\(0\)" . -g "*.java" -g "!**/target/**"
rg -n "provider\.generate|contextManager\.compact" clawkit-engine clawkit-cli -g "*.java" -g "!**/target/**"
```

第二条 grep 允许 gateway/pipeline 实现命中；最终以 ArchUnit 结果为准。

### 11.4 完成定义

只有以下全部成立才把 TODO 的 P0-R 父项标为 `[x]`：

- 全量编译、全模块测试、evaluation 测试均成功。
- ArchUnit 全部普通规则零违规，无 frozen baseline。
- ReAct、TWO_STAGE、Plan、SubAgent 行为矩阵通过。
- PLAN/ASK/AUTO 权限行为无回归。
- Provider started/terminal、tool、compact、approval 事件可在 trace 中关联。
- Session v0/v1 fixture 兼容，ephemeral 不持久化。
- CLI 查询命令不调用模型。
- `git diff --check` 通过，TODO/DESIGN 与代码事实一致。

---

## 12. 跨 PR 测试矩阵

| 链路 | 成功 | 失败/边界 | 观测 |
| --- | --- | --- | --- |
| Provider 非流式 | text、tool calls、usage | provider error、empty content | started + exactly-one terminal |
| Provider 流式 | content、tool delta、complete | callback error、throw、return-without-callback | exactly-one terminal |
| Context | 多源顺序、预算正常 | compact、HARD_LIMIT、mask | before/after sections |
| Session | v0/v1 load、save/list/delete | not found、坏 JSON、未知版本、并发保存 | 不泄露 ephemeral |
| Memory | recall、extract、explicit remember | provider failure、冲突、坏 JSON | phase=MEMORY_EXTRACT |
| Skill | catalog/load/unload | unknown、重复、路径越界 | 不写 Session |
| CLI | alias、参数命令、exit | unknown、EOF、Ctrl-C | 查询命令 provider calls=0 |
| Plan/SubAgent | worker/reviewer/child | reject、tool failure、provider failure | parent/child scope 正确 |

## 13. 方案评审

### 13.1 阻断级评审结论

1. **不能通过可变 lambda 或 engine setter 修复 Gateway 事件。** 这会形成装配时序和循环引用。应让 gateway 直接依赖稳定的 RunRecorder。
2. **不能为了让 ArchUnit 绿色而重新 freeze。** 清零条件是普通 ArchRule 通过且存储文件删除。
3. **不能让 `clawkit-memory` 实现 engine 中的接口。** 这会产生 memory → engine 反向依赖；DefaultMemoryHooks 应放 engine impl，依赖 memory 契约/实现。
4. **Session 损坏不能被 `Optional.empty()` 或 `exists=false` 吞掉。** 否则下一次 save 可能覆盖唯一恢复证据。
5. **不能一次删除所有兼容构造器再修测试。** 正确顺序是让兼容构造器委托新路径、迁移测试、最后决定是否删除。

### 13.2 高风险点与缓解

| 风险 | 后果 | 缓解 |
| --- | --- | --- |
| 流式 provider callback 与 return 双终态 | metrics 重复 | AtomicBoolean + return 后补终态测试 |
| Composite recorder 单点抛异常 | Agent run 被观测系统拖垮 | delegate 隔离、逐个 catch、测试 |
| 移除 system prompt 后消息顺序变化 | 模型行为回归 | Pipeline 快照和多轮集成测试 |
| Session 签名迁移误写旧文件 | 用户历史损坏 | schema 不升级、fixture、原子写、禁止批量迁移 |
| Memory afterRun 在失败路径执行 | 错误内容被记忆 | 仅成功 run、阈值、冲突/去重、失败隔离 |
| CLI 一次拆太大 | 命令行为回归 | parse/handler/ReplLoop 分层测试，按领域迁移 |
| 子 Agent 共享可变上下文 | 父子消息串扰 | pipeline 无状态、SessionHistory 独立、recorder 共享但 scope 独立 |

### 13.3 可合入结论

方案在以下前提下可实施：

- 严格遵守 PR-9 → PR-15 顺序。
- 每个 PR 都包含自动化测试和旧路径删除，不接受“只新增接口/类”。
- Claude 负责单 PR 内实现和自测；DeepSeek 负责独立检查旁路、失败路径、模块依赖和测试缺口，不能只审代码风格。
- 任一 PR 发现需要改变磁盘 schema、Provider 公共协议或权限语义时停止，单独提交设计变更，不夹带在重构 PR 中。

## 14. 给 Claude + DeepSeek 的执行模板

每个 PR 开始时复制以下任务模板：

```text
目标：只实施 docs/p0-r-completion-plan-review.md 中的 PR-N。

开始前：
1. 阅读 CLAUDE.md、DESIGN.md、TODO.md 和本方案对应章节。
2. 核对当前代码，不以文档状态代替代码事实。
3. 列出本 PR 将修改的文件和先写的测试。

实施约束：
- 不修改无关功能，不清理用户已有改动。
- 不通过吞异常、删断言、freeze 新违规换取绿色测试。
- 新路径测试通过后必须删除本 PR 对应旧路径。
- 权限、Session 磁盘格式、CLI 外部行为默认不变。

完成输出：
1. 修改文件列表和关键设计决策。
2. 删除了哪些旧路径。
3. 执行的测试命令及结果。
4. 尚存风险或下一 PR 的明确前置条件。
5. DeepSeek 按阻断问题、行为回归、模块边界、测试缺口、维护性顺序复审。
```
