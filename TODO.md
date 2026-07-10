# clawkit TODO

> `[ ]` 待做 `[~]` 进行中 `[x]` 已完成

本 TODO 按“底层基座 Agent 基本功”排序。模块分层见 [CLAUDE.md](CLAUDE.md)。  
本文件是可执行路线图，不写泛泛想法；每个任务都应有边界、前置条件和验收标准。

## TODO 维护规则

- 完成任意一项后，必须同步修改本文件，把对应任务从 `[ ]` 改为 `[x]`。
- 如果任务只完成了一部分，保持 `[ ]`，或在明确进入实施中时改为 `[~]`；不要为了显得进度好看而提前划掉。
- 划掉任务时，要在该任务下补充完成记录：完成日期、核心改动、验证命令或验证方式。
- 父任务拆成多个子步骤时，只有所有子步骤和验收标准都满足，父任务才能标记为 `[x]`。
- 没有跑测试或无法验证时，不能只写“已完成”；必须写明未验证原因和剩余风险。

## TODO 减重原则

本文件不是一次性偿还所有技术债的清单，而是演进路线。执行时只允许按阶段推进：

- **当前必做**：能支撑下一轮重构的最小闭环，例如护栏测试、基础 metrics、工具执行契约。
- **当前不做**：完整评测平台、数据库化观测、Web dashboard、多用户协作、复杂垂类场景。
- **能本地文件解决的，不先上数据库**：观测第一阶段使用 `.clawkit/runs/**/metrics.jsonl`、`trace.jsonl`、`summary.json`。
- **能自动判断的先评估**：先评估测试是否通过、工具是否调用、安全边界是否守住，不急着评估回答质量。
- **每个阶段只证明一件事**：P0 证明“可观测、可回归、可安全重构”；P1 再证明“完成率更高”；P2 再证明“成本更低”。

## 优先级定义

| 优先级 | 目标 | 判断标准 |
|--------|------|---------|
| P0 | 可靠运行闭环 | 上下文可信、工具安全、过程可观测、任务可评测 |
| P1 | 任务完成率提升 | 减少失败、减少重复调用、提高复杂任务完成质量 |
| P2 | 成本与效率优化 | 降 token、降延迟、降模型成本，但不牺牲可靠性 |
| P3 | 高级扩展能力 | 后台任务、远程运维、语义搜索、native image 等 |

---

## 执行阶段建议

当前最重要的不是继续堆新能力，而是先把后续演进的地基打稳。

1. **P0：可靠运行闭环**  
   先建立观测和度量能力：tokenizer、上下文预算、P0-O 结构化指标。没有指标，后续重构无法量化证明"变好了"。
2. **P0-S：重构前最小护栏**  
   在 metrics 基础上补护栏测试和工具执行结果模型，让回归有据可查。
3. **P0-R：底层结构重构**  
   在观测和护栏测试都具备后，拆 `AgentEngine`、工具执行、上下文管线和 MCP 风险模型。每一步重构都能用 metrics 和护栏测试验证。
4. **P0-D：工程闭环与项目可用性**  
   补 CI、Docker、示例、配置体验和文档入口，让项目更容易运行、验证和展示。
5. **P1/P2：完成率与效率优化**  
   在观测能力稳定后，再做缓存、截断、fallback、多模型路由等优化。
6. **P3：高级扩展能力**  
   后台任务、远程运维、语义搜索、native image 等能力放到基座稳定之后。

---

## 当前 Harness 能力盘点

一个成熟的 Agent harness 至少包括：上下文管理、工具系统、执行编排、状态与记忆、评估与观察、约束与恢复。clawkit 已经具备原型能力，但还没有达到成熟工程标准。

| 维度 | 当前已经有的能力 | 主要不足 | 后续落点 |
|------|------------------|----------|----------|
| 上下文管理 | system prompt、会话历史、working memory、相关历史会话注入、`/context`、compact、MessageMasker、LadderedCompactor | token 仍偏估算；上下文预算线不清晰；runtime context 和持久会话存在污染风险；compact 后保留内容缺少可解释记录 | P0 真实 tokenizer、上下文预算管理；P0-R 拆 `ContextPipeline`、分离运行时上下文和持久会话 |
| 工具系统 | 内置文件、shell、搜索、时间等工具；MCP 动态注册；`PLAN/ASK/AUTO` 权限模式；SafetyInterceptor；只读工具概念 | 工具返回值仍偏字符串；`ToolMetadata` 不完整；MCP 工具 readOnly/riskLevel 粗糙；审计 JSONL 不稳定；工具失败不可统一统计 | P0 Git 专用工具、WriteTool 覆盖确认；P0-R 统一工具执行契约、重构 MCP 风险模型、修复审计日志 |
| 执行编排 | ReAct 主循环、工具调用、Plan-and-Execute、SubAgent、慢思考阶段、最大轮次、进度提醒、死循环提醒 | `AgentEngine` 职责过重；状态机不显式；工具执行、上下文、记忆、计划混在主循环；恢复策略和失败分类不足 | P0-R 拆 `AgentEngine`、`ToolCallExecutor`、`PlanRuntime`、`SkillRuntime`；P1 fallback 和任务切分 |
| 状态与记忆 | session JSON、DiskMemoryService、working memory、相关会话检索、`.clawkit` 数据目录 | 运行时事实和长期会话边界不够清楚；记忆去重、衰减、冲突处理弱；session schema/versioning 需要收敛 | P0-R 分离运行时上下文和持久会话、`MemoryHooks`；P1 记忆质量与召回优化 |
| 评估与观察 | 基础日志、`/context` 统计、部分工具审计、TODO 中有测试记录 | 当前最明显短板：没有统一 `metrics.jsonl`；没有 benchmark runner；没有任务完成率、工具成功率、耗时、token、compact、provider retry 的结构化统计；无法量化重构是否变好 | P0 结构化指标、评测基准；P0-R 工具执行结果模型；后续建立回归评测和观测报表 |
| 约束与恢复 | `PLAN/ASK/AUTO`、审批拦截、路径安全检查、shell timeout、Provider retry/circuit breaker、部分敏感信息遮蔽 | 风险模型偏粗；MCP 高风险动作识别不足；失败恢复多靠提示词和重试；缺少可回滚、可恢复、可审计的任务状态 | P0 WriteTool 覆盖确认；P0-R MCP 风险模型、BashTool 生命周期、Provider 输出协议；P1 错误分类与 fallback |

结论：项目已经不是“只有提示词的 demo”，而是有了 harness 雏形；但成熟度还卡在工程闭环上，尤其是评估与观察。没有 metrics 和 benchmark，后续重构很难证明“变好了”，只能凭体感判断。

---

## P0：可靠运行闭环

这些是基座 Agent 的基本功，优先级高于新增花哨能力。

- **[x] 真实 tokenizer**（context）  
  引入 JTokkit 或等价 tokenizer，替换字符数估算。  
  验收：`/context` 展示真实 token；上下文压缩触发点不再依赖字符估算。

  ✅ 2026-07-05 — JTokkit 替换字符估算，LLMConfig 按模型检测 contextWindow/encoding，AgentEngine 移除硬编码 8000。

- **[x] 上下文预算管理**（context / engine）  
  建立模型窗口预算线，例如 70% 预警、85% 强制 compact。按 system、tools、history、memory、tool result 分区预算。  
  验收：任务运行中能解释 token 分布；压缩前后保留关键约束。

  ✅ 2026-07-08 — 新增 ContextBudgetPolicy(70/85/95)、ContextBudgetAnalyzer(7 分区)、CompactionResult；engine 根据预算状态决策 compact，compact 后一律重分析，>95% 硬拒绝；/context 显示分区；ConstraintExtractor 提取+验证关键约束。

### P0-O：评估与观测最小闭环

目标不是先做完整 eval 平台，而是在大规模重构前建立工程回归能力：能知道一次 run 做了什么、哪里失败、重构后有没有退化。

- **[x] 本地观测存储格式**（observability）  
  第一阶段不引入 MySQL / PostgreSQL。使用本地文件：
  - `.clawkit/runs/<run-id>/metrics.jsonl`
  - `.clawkit/runs/<run-id>/trace.jsonl`
  - `.clawkit/runs/<run-id>/summary.json`
  验收：每次任务运行生成独立 run 目录；文件可直接打开阅读；敏感字段脱敏。
  
  ✅ 2026-07-10 — 新增 clawkit-observability 模块；FileRunRecorder 写 Jackson JSONL + summary.json；参数脱敏（仅写 argSummary）。

- **[x] 最小 Metrics 数据模型**（observability）  
  先定义够用的结构，不追求完整平台。  
  覆盖：
  - `RunMetrics`：runId、任务摘要、开始/结束时间、总耗时、状态、失败类型。
  - `TurnMetrics`：轮次、模型、输入/输出 token、provider 耗时、是否重试。
  - `ToolCallMetrics`：工具名、readOnly、riskLevel、成功/失败、耗时、输出字节数、是否截断、是否审批。
  - `ContextMetrics`：system、tools、history、memory、tool result 的 token 分布，以及 compact 前后变化。
  验收：每个 run 至少能汇总轮次、工具调用次数、工具失败率、耗时和 compact 次数。
  
  ✅ 2026-07-10 — RunMetrics/TurnMetrics/ToolCallMetrics/CompactMetrics/ProviderCallMetrics 全部 record 定义在 clawkit-observability/model/；RunStatus 枚举 10 个状态；RunEvent sealed interface 10 种子类型。

- **[x] AgentEngine 观测打点**（engine / observability）  
  在 run start/end、turn start/end、tool call start/end、compact start/end、provider retry、approval decision 位置打点。  
  前置：先收敛工具执行结果模型，至少要能拿到工具名、成功/失败、耗时、输出字节数。  
  验收：一次普通任务的 trace 能还原执行链路；工具失败和 provider retry 能在 metrics 中看到。
  
  ✅ 2026-07-10 — AgentEngine 新增 onRunEvent 监听器 + fireRunEvent 分发；在 6 个 exit point + turn loop + tool counter + compact counter 打点；approval 事件因作用域限制推迟到后续重构；ObservableLLMProvider decorator 记录 provider 调用耗时。

- **[x] CLI 观测入口**（cli / observability）  
  提供轻量查看能力，不先做 dashboard。  
  目标：
  - `/metrics` 查看最近一次 run 汇总。
  - `/runs` 列出最近若干 run。
  - `/trace <runId>` 查看关键事件摘要。
  验收：不打开源码也能知道最近一次任务用了多少轮、调用了哪些工具、哪里失败。
  
  ✅ 2026-07-10 — ClawkitApp 新增 /runs、/metrics、/trace 三个 slash command；ClawkitCompleter/printMenu/printHelp/resolveCommand 同步更新；RunReader 从磁盘读取 run 记录。

- **[ ] 最小 Benchmark Runner**（evaluation）  
  建立 10-20 个固定任务，先覆盖可机械判断的场景：读代码、改小 bug、改文档、跑测试、权限边界、工具失败、长输出、compact 保留关键约束。  
  每个 case 至少包含：任务描述、初始 workspace、允许工具、最大轮次、自动校验命令或校验规则。  
  验收：一条命令跑完 benchmark，并输出 success、avgTurns、avgToolCalls、avgDuration、toolFailureRate。

- **[ ] 回归对比报告**（evaluation）  
  不做复杂智能评分，先对比工程指标。  
  对比维度：
  - 成功率是否下降。
  - 平均轮次、耗时、工具调用次数是否明显上升。
  - 工具失败率、provider retry、compact 次数是否异常。
  - `PLAN/ASK/AUTO` 安全边界是否被破坏。
  验收：能把 current run 和 baseline summary 做对比，输出“退化 / 持平 / 改善”的简单结论。

暂不做：MySQL 观测库、Web 报表、复杂人工评分平台、多模型大规模评测。这些等本地 JSONL 和 benchmark 稳定后再考虑。

### P0-O 方案评审遗留问题（2026-07-10 评审发现，待实施）

- **[ ] Provider retry 事件捕获**（observability）  
  AgentEngine 当前无法感知 OpenAIProvider 内部重试——retry 逻辑封装在 `sendWithRetry()` 内。  
  方案：ObservableLLMProvider decorator 已创建，但需在 ClawkitApp 装配时实际替换 provider 实例，并在 retry 计数能力可用后接入。

- **[ ] `runPlanExecute()` 观测打点补充**（engine / observability）  
  当前打点仅覆盖主 `run()` 方法（line 499），`runPlanExecute()`（line ~1631）是完全独立的代码路径，有独立的 provider 调用和 6 个 exit point。  
  方案：为 `runPlanExecute()` 添加 RunStarted/RunCompleted 事件。

- **[ ] SubAgent 运行隔离**（engine / observability）  
  `spawnSubAgent()`（line ~980）创建独立 AgentEngine 实例，子 agent 的 run 应产生独立 `<sub-run-id>/` 目录。当前子 run 的 FileRunRecorder 未被注册。  
  方案：在子 AgentEngine 创建时注册 FileRunRecorder，父 run trace 中记录子 run ID 引用。

- **[ ] internal tools 补充 fireToolStart/End**（engine）  
  6 个 engine-internal tools（task/session_context/skill_load/skill_unload/memory_save/remember，lines ~867-900）不触发 fireToolStart/fireToolEnd，trace 链路不完整。  
  方案：在 executeSequential 中为 internal tools 补充事件触发。

- **[ ] approval 事件接入观测**（engine / observability）  
  approval 决策在 `executeSequential()` 内部（line ~955），无法访问 `run()` 方法中的 `currentRunId` 局部变量。当前 approval 事件未写入 trace。  
  方案：将 currentRunId 提升为 AgentEngine 字段或以参数传递；或等 P0-R 拆分 executeSequential 后一并处理。

- **[ ] Git 专用工具**（tools）  
  封装 `git status`、`git diff`、`git log`、`git show`。  
  验收：PLAN 模式可读；输出结构化；无需通过 bash 才能获得常用 git 信息。

- **[ ] WriteTool 覆盖确认**（tools / safety）  
  目标文件已存在时触发确认或强制参数。  
  验收：`ASK` 模式可拦截覆盖；`AUTO` 模式也不能静默覆盖非空文件，除非显式传入 overwrite。

---

## P0-R：底层结构重构

目标：把运行时状态、持久会话、工具执行、上下文管线和安全审计拆清楚。详细设计原则见 [DESIGN.md](DESIGN.md)。

### 重构约束

- 每次只移动一个职责边界，不在同一个 PR 中同时做大功能、新接口和大规模格式化。
- 先补行为测试，再移动代码；重构 PR 默认不改变外部行为。
- 行为变化必须写进验收标准和测试名，不能靠口头说明。
- `AgentEngine`、`ClawkitApp`、`ToolRegistry`、`ContextPipeline` 相关改动必须说明模块边界是否变好。
- 重构期间可以保留旧入口做适配层，等新路径覆盖稳定后再删除旧逻辑。

### 结构风险

- `AgentEngine` 混合主循环、工具执行、审批、compact、记忆、session、skill、plan、todo 追踪。
- `ClawkitApp` 混合 REPL、slash command、审批 UI、session、memory、MCP、skill、IM。
- runtime system message 和持久会话边界不清。
- 工具风险模型、MCP 审计、Bash 生命周期、Provider 输出协议仍偏原型。

- **[ ] 建立重构护栏测试**（engine / cli / tools）  
  覆盖 PLAN 只读、ASK 审批、runtime context 不持久化、compact 保留关键约束、Bash 长输出/超时、MCP 风险、slash command 不调 LLM。  
  验收：P0-R 每一步重构前后都能跑对应测试。

- **[ ] 拆分 `AgentEngine` 上帝类**（engine）  
  按小 PR 拆出 `ToolCallExecutor`、`ContextPipeline`、`MemoryHooks`、`SkillRuntime`、`PlanRuntime`。  
  验收：`AgentEngine` 只保留编排门面；每个拆分组件有独立单元测试；现有 AgentEngine 行为测试继续通过。

- **[ ] 拆分 CLI 命令和交互层**（cli）  
  拆出 `ReplLoop`、`SlashCommandRouter`、命令组、`ApprovalConsole`、`ConsoleRenderer`。  
  验收：`ClawkitApp` 只负责装配依赖和启动；slash command 可独立单测；IM 开关不影响普通 CLI 流程。

- **[x] 分离运行时上下文和持久会话**（engine / context）  
  runtime context 不写入 session history，所有注入内容标记来源和生命周期。  
  验收：连续多轮运行不会重复累积 `[Working Memory]`、`[Runtime]`、`[Related Past Sessions]` system 消息。

  ✅ 2026-07-08 — 新增 workspaceContext / memoryContext / runtimeContext 三个 ephemeral 容器；sniffWorkspaceState、injectRelatedSessions、[Runtime]、[Working Memory] 重定向到对应容器；contextHistory = new ArrayList<>(sessionHistory) 打破别名；autoSaveSession 过滤 runtime 消息；clearSession 清空 ephemeral 容器。

- **[~] 建立 `ContextPipeline` 输入输出模型**（engine / context）  
  明确输入 `SessionHistory`、`RuntimeContext`、`MemoryContext`、`WorkspaceContext`、`SkillContext`，输出 `ModelContext` 和 `EphemeralContext`。  
  验收：上下文管线可以独立测试；一次 run 结束后能明确哪些内容被持久化，哪些只是本轮注入。

  🔶 2026-07-08 — 容器模型已建立（workspaceContext/memoryContext/runtimeContext + assembleModelContext()）；预算模型已就绪（ContextBudgetAnalyzer/ContextBudgetPolicy/CompactionResult）。Pipeline 作为独立组件推迟到后续 PR。

- **[ ] 重构 MCP 工具元数据和风险模型**（tools / mcp / engine）  
  支持 `readOnly`、`riskLevel`、`destructive`、`requiresApproval`，未知写工具默认按中高风险处理。  
  验收：新增 MCP adapter 测试覆盖只读工具、高风险工具、未知风险工具。

- **[ ] 统一工具执行契约**（tools / engine）  
  引入 `ToolMetadata`、`ToolExecutionRequest`、`ToolExecutionResult`，由 `ToolCallExecutor` 统一审批、缓存、超时、审计和结果回注。  
  验收：engine 不直接拼工具错误；所有工具结果可以进入 metrics；新增工具不需要修改 AgentEngine 主循环。

- **[ ] 修复 MCP 审计日志 JSONL**（tools / mcp）  
  使用 Jackson 写合法 JSONL，记录时间、工具、动作、参数预览、结果、耗时、输出大小，并做脱敏。  
  验收：审计日志能被 Jackson 逐行读取；失败和成功记录结构一致。

- **[ ] 修复 BashTool 输出读取和进程生命周期**（tools）  
  并发 drain stdout/stderr，超时销毁进程，输出截断保留 head/tail 和退出码。  
  验收：长输出命令、无输出命令、超时命令、非零退出码都有测试。

- **[ ] 收敛 Provider 输出协议**（provider / engine）  
  明确 `ModelResponse`，把工具调用解析移到独立 parser，流式输出先解析和安全检查再执行。  
  验收：provider 层只负责模型通信；engine 层只消费结构化响应；协议错误不会继续进入工具执行。

推荐顺序：护栏测试 -> `ToolCallExecutor` -> `ContextPipeline` -> MCP 风险和审计 -> BashTool -> CLI -> Provider 协议。

---

## P0-D：工程闭环与项目可用性

目标：让项目更容易运行、验证、展示和维护。

- **[ ] GitHub Actions CI**（repo / ci）  
  push / PR 执行 `mvn -B test`。

- **[ ] Docker 最小运行环境**（distribution）  
  新增 `Dockerfile` 和 `.dockerignore`，封装 Java 21 + shaded jar。

- **[ ] GitHub Release 产物**（distribution / ci）  
  tag 发布时自动构建并上传可运行 jar。

- **[ ] 示例和演示路径**（docs / examples）  
  提供最小 demo，覆盖 CLI、权限模式、读写代码、测试、MCP。

- **[ ] 配置体验优化**（cli / config）  
  梳理 env 和 config 优先级，提供 example 配置和 `/config` 脱敏展示。

- **[ ] 用户可读错误信息**（cli / provider / tools）  
  常见错误给出原因、影响和下一步建议，不直接抛内部堆栈。

- **[ ] 文档分层入口**（docs）  
  README 保持轻量，详细内容进入 `docs/architecture.md`、`docs/runtime.md`、`docs/mcp.md`、`docs/development.md`。

- **[ ] GitHub 项目管理规范**（repo）  
  整理 About、Topics、Issues、Milestones，Issues 只放可执行任务。

- **[ ] 编程规范检查清单 / Skills 化**（docs / agent）  
  先把 `DESIGN.md` 规范整理成 checklist，高频流程再做 skill。

---

## P1：任务完成率提升

- **[ ] 应用层工具结果缓存**（tools / engine）  
  对 read-only 工具做 session 级缓存，metrics 记录命中率。

- **[ ] 工具结果智能截断**（tools / context）  
  长输出保留 head/tail、错误片段和匹配行，避免污染上下文。

- **[ ] 任务感知压缩策略**（context）  
  compact 根据任务类型保留不同证据。

- **[ ] Provider 降级链路**（provider）  
  主模型超时、熔断或限流后自动切备用模型，并写入 metrics。

- **[ ] 流式早期终止**（provider / engine）  
  检测明显坏输出时中止流并重试，避免错误进入工具层。

- **[ ] BashTool 增强**（tools）  
  支持工作目录、环境变量白名单、跨平台 shell 策略。

---

## P2：成本与效率优化

- **[ ] 自适应分层 compact**（context）  
  按任务复杂度选择不同压缩策略，并记录 metrics。

- **[ ] 多模型路由**（provider）  
  简单任务走小模型，复杂规划走强模型。

- **[ ] Prompt caching**（provider / context）  
  缓存 system prompt、tool schema、repo 摘要。

- **[ ] Bash 会话复用**（tools）  
  可选复用 shell session，必须支持 reset 并避免状态污染。

- **[ ] 记忆去重与合并**（memory）  
  合并相似记忆，标记冲突记忆。

- **[ ] 记忆衰减**（memory）  
  给记忆增加时间权重，旧信息默认降权。

---

## P3：高级扩展能力

这些能力放在基座可靠性和观测能力稳定后推进。

- **[ ] `/btw` 后台并行任务**（engine / cli）  
  独立 AgentEngine 虚拟线程并发。前置：任务状态、日志、取消、资源隔离、metrics 稳定。

- **[ ] 远程运维 MCP：分阶段验证项目**（tools / mcp / ops）  
  Phase 0 本地沙箱模拟；Phase 1 只读 SSH；Phase 2 受控写操作；Phase 3 运维工作流。  
  前置：MCP 风险模型、审计、ASK 审批、Bash timeout、输出截断、失败分类稳定。

- **[ ] 任务级回滚**（tools / engine）  
  写入前保存 `.clawkit.bak`，支持按 task/action 回滚。

- **[ ] 本地嵌入模型语义搜索**（memory / context）  
  用 embedding 检索历史会话、代码片段、文档片段。

- **[ ] GraalVM native-image**（cli）  
  提供原生二进制版本，降低冷启动时间。

---

## 暂不优先

- 预测性工具预热：主要节省 50-200ms，低于上下文和工具可靠性优先级。
- 大而全垂类业务包：应作为上层插件/MCP/Skill，不写入底层引擎。
- 自动高风险写操作：没有人审、审计、回滚之前不做。

---

## 最近测试记录

最近文档记录（2026-07-05 前记录）：295/295 通过（tools 66 + provider 14 + context 45 + memory 32 + engine 69 + im 23 + cli 46）。

本记录用于路线参考。每次实际合入前仍需重新跑相关测试。
