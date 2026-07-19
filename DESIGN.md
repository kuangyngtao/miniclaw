# clawkit 设计规范

本文档记录长期稳定的工程设计约束，回答“代码应该如何组织和演进”。项目定位看 [CLAUDE.md](CLAUDE.md)，当前实施任务看 [TODO.md](TODO.md)。

## 设计原则

1. 底层保持通用，垂类能力通过 MCP、Skill、工具包、插件或 workflow 接入。
2. 解耦用于隔离变化，不以增加接口、类和模块数量作为目标。
3. 编排层只决定流程，具体执行、解析、持久化和展示由独立组件负责。
4. 权限先于能力；任何副作用先定义风险、审批、审计和失败行为。
5. 运行时上下文是缓存，磁盘上的 session、计划、记忆、审计和指标才是可追踪事实。
6. 已知失败必须结构化、可解释、可恢复；未知失败保留 cause。
7. 没有指标和回归测试的性能或可靠性优化不能宣称有效。

## 模块与依赖

| 模块 | 公共职责 | 禁止承担 |
| --- | --- | --- |
| `clawkit-tools` | Tool 契约、结构化执行结果、内置工具、MCP adapter、安全拦截、ExecutionControl/ActionDescriptor/OutputEnvelope 契约 | Agent loop、CLI、Provider 协议 |
| `clawkit-reliability` | CancellationTree、BudgetLedger、失败决策表、Attempt journal/状态机、目标互斥、SideEffectGate、DeterministicVerifier、RecoveryScanner | Agent loop、CLI、Provider 协议、具体 Ops 领域 |
| `clawkit-provider` | 模型请求、响应解析、流式协议、重试、熔断、降级 | 工具执行、上下文压缩、业务决策 |
| `clawkit-context` | Prompt 组装、预算、压缩、消息裁剪、Skill 上下文 | 会话持久化、工具副作用 |
| `clawkit-memory` | 记忆存储、检索、去重、冲突和衰减 | Agent 流程 |
| `clawkit-observability` | RunEvent、指标投影、记录器、reader | Agent 决策和 UI |
| `clawkit-engine` | AgentRuntime、PlanRuntime、工具编排、会话协调 | 终端 UI、具体 Provider JSON、具体工具实现 |
| `clawkit-im` | 消息通道 adapter | Agent 状态机和业务规则 |
| `clawkit-cli` | composition root、REPL、slash command、展示、审批 UI | 核心执行规则 |

依赖约束：

- 依赖必须单向、显式、无循环。
- `tools`、`memory`、`reliability` 不依赖 `engine`、`cli`、`im`；`reliability` 只依赖 `tools` 契约。
- `provider` 和 `context` 可以依赖稳定的 tools schema，但不能依赖 engine。
- `observability` 只依赖生成指标所需的稳定数据契约，不反向控制 engine；RunEvent 写入失败不得改变控制面状态，Reliability Journal 写入失败必须阻断写动作。
- `engine` 是运行时组装点，只消费抽象契约。
- `cli`/`im` 只适配输入输出。composition root 可以实例化具体实现，但依赖必须在 POM 中显式声明。
- 跨模块数据使用 record、enum、sealed interface 或窄接口；禁止以 `Map<String, Object>` 作为长期公共协议。

## 运行时边界

目标执行结构：

```text
Input adapter
  -> AgentRuntime
      -> ContextPipeline
      -> LLMProvider
      -> ModelResponse
      -> ToolCallExecutor
          -> PermissionPolicy
          -> ToolRegistry / InternalToolRouter
          -> ToolExecutionResult
      -> MemoryHooks / SessionService
      -> RunEvent sink
  -> Output adapter
```

### AgentRuntime

Agent runtime 管理一次任务的生命周期，只负责：

- run/turn 状态推进和最大轮次。
- 调用 ContextPipeline、Provider 和 ToolCallExecutor。
- 根据结构化结果决定继续、完成、失败或中断。
- 发出带 run/turn 作用域的事件。

它不应直接：

- 解析 Provider JSON/SSE。
- 按工具名推断风险或拼装工具错误文本。
- 读写终端、用户 HOME 或具体日志文件。
- 组装 memory/skill/runtime system message。
- 执行具体工具或维护多套并行/串行工具逻辑。

一次 run 的可变状态应集中在显式 `RunContext`/`AgentRuntime` 实例中。跨 run 共享的配置和服务尽量不可变；`currentRunId`、turn、临时消息等不得成为可被并发 run 覆盖的全局状态。

### 执行模式

| 模式 | 用途 | 必须共用的底层链路 |
| --- | --- | --- |
| ReAct | 开放式探索和代码任务 | ContextPipeline、Provider、ToolCallExecutor、RunEvent |
| TWO_STAGE | 先规划再执行 | 两阶段均使用 Provider 观测；第二阶段共用工具链 |
| Plan-and-Execute | 结构化多步任务 | PlanRuntime 调用同一 ToolCallExecutor 和权限策略 |
| SubAgent | 独立或并行子任务 | 独立 run scope、同一工具/观测契约、禁止递归派发 |

执行模式可以改变编排，不能创建权限、审计和工具执行旁路。

## 工具执行契约

唯一执行入口是 `ToolCallExecutor`。普通工具、MCP、Plan、SubAgent 和 engine-internal tools 必须进入同一链路。

### ToolMetadata

每个工具至少声明：

- `name`、`description`、窄化的 `inputSchema`。
- `readOnly`、`riskLevel`、`destructive`、`requiresApproval`。
- 副作用集合、timeout 策略和输出限制。

未知工具使用保守默认值：非只读、高风险、可能破坏、需要审批。不得由 UI 或 engine 按名称维护第二套风险表。

### ToolExecutionRequest / Result

请求应包含 toolCallId、工具名、结构化参数，以及需要的执行作用域。结果至少表达：

- 成功或失败。
- errorCode、message 和原始 cause/详情。
- duration、outputBytes、truncated、exitCode。
- metadata、审批决策和审计关联信息。

旧的 `execute(String) -> Result<String>` 只允许作为迁移适配层。适配层不得吞异常、丢失错误码或把坏参数替换成空对象继续执行。

### 执行顺序

- 多个只读、相互独立的工具可以并行。
- 写工具、高风险工具、internal tools 默认串行。
- 并行任务必须有取消、超时和异常归并；任一线程失败不能让 latch 永久等待。
- 结果按原 tool call 顺序回注，事件携带实际完成时间。

### Bash 与进程

- stdout/stderr 必须并发 drain，避免缓冲区阻塞。
- timeout 后先正常终止，再强制终止进程树；返回明确 timeout 状态。
- 结果保留 exitCode；非零退出码不得伪装成成功。
- 截断保留 head/tail、总字节数和 truncated 标记。
- 工作目录固定在 workspace，环境变量使用白名单或显式传入。

## 权限与安全

| 模式 | 行为 |
| --- | --- |
| `PLAN` | 只暴露和执行只读工具 |
| `ASK` | 写操作、高风险或要求审批的工具执行前请求人工决定 |
| `AUTO` | 可自动执行允许的操作，仍受 SafetyInterceptor、workspace 和审计限制 |

规则：

- 权限判断读取 ToolMetadata，不读取提示词或工具名硬编码。
- 未知 MCP 工具默认高风险，不能因缺失元数据降为只读。
- 写文件默认不能静默覆盖非空文件，除非显式 overwrite 或审批结果允许。
- 文件路径必须 normalize 并验证位于允许 root 内；额外只读 root 不能变成写 root。
- 审批结果必须区分 approve、approve-same-type、reject、modify，并进入结果与 RunEvent。
- SafetyInterceptor 是最后防线，AUTO 也不能绕过。

## Provider 契约

Provider 层只负责通信和协议，不理解任务业务。

### ModelResponse

engine 消费统一 `ModelResponse`，不直接消费 OpenAI/Anthropic DTO。它至少表达：

- assistant content、reasoning、tool calls。
- finish reason、模型、token usage。
- 协议错误或部分流式结果。

工具参数 JSON 解析失败属于协议错误，不能静默变成 `{}` 并触发工具。

### 失败分类

至少区分：鉴权、限流、timeout、上下文过长、schema/protocol、服务端错误、网络错误、取消。重试只用于明确可重试的类别，并遵守最大次数和退避策略。

每次 Provider 调用必须携带 request-scoped 的 runId、turn、phase、streaming、duration、retryCount、token usage 和最终错误。慢思考、compact、记忆抽取和 Plan 不能成为观测盲区。

## 上下文、会话与记忆

### ContextPipeline

唯一模型上下文组装入口接收：

- 稳定 system prompt 与 workspace rules。
- 持久 `SessionHistory`。
- `WorkspaceContext`、`RuntimeContext`、`MemoryContext`、`SkillContext`。
- Tool definitions/results 和预算策略。

输出 `ModelContext` 以及可解释的预算报告。每个片段需标明 source、lifecycle、priority、token count、是否允许 compact 和是否允许持久化。

### 持久化边界

Session 只保存真实对话事实：用户输入、助手响应、工具调用和工具结果。以下内容默认 ephemeral：

- `[Runtime]` 提醒和循环警告。
- Working memory 展示文本。
- Related sessions 注入。
- Workspace 快照和临时 Skill 提示。
- TWO_STAGE 的中间推理消息。

自动保存前仍需执行持久化过滤，防止上游误用污染历史。Session schema 应有版本号。

### Compact

- 触发阈值来自明确的模型窗口和预算策略。
- compact 前后都记录分区 token、保留约束和丢弃内容摘要。
- 必须保留用户约束、文件路径、错误证据、未完成 todo 和审批边界。
- compact 后重新预算；超过硬限制时结构化失败，不继续盲目调用模型。

### Memory

- 记忆标明来源、时间和置信度，不伪装成当前事实。
- 自动写入可关闭、可审计、可去重。
- 冲突记忆保留冲突或降权，不静默覆盖。
- MemoryHooks 负责 run 前召回和 run 后提取，Agent loop 不直接操作具体 store。

## 观测设计

RunEvent 是运行时与持久化的边界。业务代码只发事件，不直接写 JSONL。

### 事件作用域

- 每个事件包含 runId；turn/provider/tool 事件还包含 turnNumber。
- 每个 RunStarted 恰有一个 RunCompleted。
- 并发 run 按 runId 管理独立 recorder 状态和 writer。
- SubAgent 使用独立 runId，并通过 parentRunId 或显式父子事件关联。

### 本地文件契约

```text
.clawkit/runs/<run-id>/
  events.jsonl
  summary.json
```

- `events.jsonl` 是唯一事实来源，每行一个 `RunEventEnvelope`，包含 `RunEventPayload` 的 13 种类型化子类型。
- `summary.json` 由 `RunAccumulator` 从事件聚合生成，原子写入，是 events 的快照索引。
- `/metrics` 从 events 动态投影指标（`RunMetricsProjector` + `RunAccumulator`），不持久化第二份 JSONL。
- 原始 prompt、工具参数、工具输出和敏感凭据不落盘；只写有长度限制的脱敏摘要。
- Reader 流式逐行容错：单行损坏跳过并报告 warning，不让整份记录不可用。
- 并发 run 按 `ConcurrentHashMap<String, RunState>` 管理独立 writer、lock、sequence 和 accumulator。

## CLI 与 IM

- `ApplicationBootstrap` 是唯一 composition root；同一种服务只能装配一次。
- `ClawkitApp` 只负责进程入口和启动，不维护业务状态。
- `ReplLoop` 管输入循环和中断；`SlashCommandRouter` 管命令分发；`ApprovalConsole` 管人工审批；`ConsoleRenderer` 只格式化输出。
- Slash command 不应为了查询状态调用 LLM。
- CLI 和 IM 共用同一应用服务，不复制 Agent 执行逻辑。
- IM 的并发消息必须遵守 engine 的 busy/queue/cancel 约束，通道关闭必须释放线程和连接。

## 错误与生命周期

结构化错误至少包含 code、message、details、retryable 和 cause（适用时）。

- 查询方法不得产生隐藏写入。
- catch 后不能只返回 `"failed"` 或空字符串。
- InterruptedException 必须恢复中断标志并停止相关工作。
- 资源使用 try-with-resources 或明确 close；run 失败也必须关闭 writer/process/transport。
- 时间、用户目录、环境变量、网络 transport 和文件 store 应可注入，便于测试。

## 代码约束

- Java 21；不可变数据优先 record，有限状态优先 enum/sealed interface。
- 构造器注入依赖，避免静态全局可变状态。
- 单类超过 300 行检查职责，超过 500 行原则上必须拆分。
- 单方法超过 60 行或嵌套超过 3 层时检查是否混合校验、编排、执行和格式化。
- 核心逻辑不直接 `System.out`，由日志或输出 adapter 处理。
- 配置必须有默认值、来源、优先级和边界校验。

## 测试规范

| 层级 | 目标 |
| --- | --- |
| Unit | 纯逻辑、schema、风险映射、错误分类 |
| Component | ToolCallExecutor、ContextPipeline、RunRecorder、parser |
| Integration | 各执行模式跨模块链路、权限和持久化 |
| Manual | 真实模型、网络、IM 和外部 MCP |
| Benchmark | 固定任务完成率、轮次、耗时、工具失败率 |

硬性要求：

- bug fix 必须有回归测试，除非无法自动化并说明原因。
- 测试不得依赖真实 API key、真实网络或真实用户 HOME。
- 文件测试使用临时目录；覆盖 Windows 路径与 workspace 逃逸。
- Provider 使用 fake transport；MCP 覆盖只读、高风险、未知风险和异常 transport。
- Bash 覆盖 stdout/stderr、长输出、无输出、timeout、非零退出码和取消。
- Engine 覆盖普通 ReAct、TWO_STAGE、Plan、SubAgent、internal tools 的成功与失败。
- Observability 覆盖合法 JSONL、脱敏、失败收尾、并发 run 隔离和 reader 容错。
- 测试名表达行为，例如 `shouldRejectUnknownMcpToolInPlanMode`。

## 代码审查

审查顺序：

1. 阻断问题：权限绕过、workspace 逃逸、上下文污染、密钥、数据损坏。
2. 行为回归：执行模式、错误、取消、持久化、兼容性。
3. 模块边界：旁路、循环依赖、传递依赖、外部 DTO 泄漏。
4. 测试缺口：成功、失败、边界和并发。
5. 设计与维护性：命名、重复、职责和复杂度。

出现以下情况不得合入：

- 写文件、执行命令、远程调用或发消息没有权限判断和审计。
- PLAN 可执行写工具，ASK 可绕过审批，AUTO 可绕过 SafetyInterceptor。
- runtime/memory/related-session 临时文本被持久化到 session。
- Provider 坏工具参数仍进入真实工具。
- 新执行路径绕过 ToolCallExecutor 或 RunEvent。
- 核心边界改动没有回归测试。
- 提交包含密钥、Token、Webhook 或私有配置。

## 渐进式重构

1. 用行为测试固定旧路径。
2. 创建新组件和结构化契约，保留最薄适配层。
3. 迁移一条实际运行路径。
4. 验证成功、失败、权限和观测。
5. 迁移剩余路径。
6. 删除旧代码和重复状态。
7. 更新 TODO 状态和文档。

只有运行路径已经切换、旧逻辑已删除、测试和观测均通过，重构任务才能标记完成。
