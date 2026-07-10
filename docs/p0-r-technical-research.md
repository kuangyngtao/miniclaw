# P0-R 底层结构重构技术调研

调研日期：2026-07-10

## 背景

本调研针对 `TODO.md` 中 P0-R「底层结构重构」任务：把运行时状态、持久会话、工具执行、上下文管线和安全审计拆清楚。

本次只做技术调研和排序建议，不改变 `TODO.md` 任务状态。依据文件：

- `TODO.md`：P0-R 任务范围、重构约束、推荐顺序。
- `DESIGN.md`：模块边界、接口设计、工具权限、测试和重构规范。
- 当前实现：`AgentEngine`、`ClawkitApp`、tools、MCP、Bash、Provider、observability。

## 总体结论

当前代码已经具备 P0-R 的拆分基础，但还不适合直接大规模拆 `AgentEngine`。最小安全路径应先补齐“可观测事件贯通 + 重构护栏测试”，再引入统一工具执行契约。原因是 observability 的数据模型已经存在，但主循环还没有把 tool/provider/compact/approval 的结构化事件真正打通；如果此时先拆类，重构退化很难用指标证明。

推荐实际顺序：

1. 护栏测试和观测事件贯通。
2. `ToolMetadata` / `ToolExecutionRequest` / `ToolExecutionResult` 兼容层。
3. `ToolCallExecutor`。
4. `ContextPipeline`。
5. MCP 风险模型和审计 JSONL。
6. BashTool 生命周期。
7. CLI router / renderer 拆分。
8. Provider `ModelResponse` 和 parser 收敛。

这与 TODO 推荐顺序基本一致，但建议把“观测事件贯通”作为 `ToolCallExecutor` 前置条件。

## 当前结构画像

### AgentEngine

`AgentEngine` 仍是主要上帝类。它同时承担：

- 主 ReAct loop：`run()`。
- 上下文预算、mask、compact。
- 工具串行/并行执行。
- ASK/PLAN/AUTO 权限判断和审批。
- runtime/memory/workspace context 注入。
- session 保存/加载。
- working memory 和 memory extraction。
- sub-agent 派发。
- plan-and-execute 入口。
- todo/plan 文件同步。
- CLI 可视事件回调。
- observability 事件分发。

证据：

- 类入口：`clawkit-engine/src/main/java/com/clawkit/engine/impl/AgentEngine.java:77`
- 持久会话与三类 ephemeral context：`AgentEngine.java:405`, `AgentEngine.java:442-444`
- 主循环：`AgentEngine.java:499`
- 上下文组装：`AgentEngine.java:606-607`, `AgentEngine.java:1536-1542`
- 工具执行分支：`AgentEngine.java:838-847`
- 串行执行和审批：`AgentEngine.java:908-989`
- Plan-and-Execute 入口：`AgentEngine.java:1675`
- 工具 UI 事件与 RunEvent 分发分离：`AgentEngine.java:2001`, `AgentEngine.java:2035`

判断：`AgentEngine` 已经超过 DESIGN 中 500 行拆分阈值很多，且职责边界与 TODO 的 P0-R 风险描述一致。第一刀不应改业务行为，而应把“工具执行路径”抽成独立组件，因为它能同时承接审批、审计、metrics、失败模型和结果回注。

### ClawkitApp

`ClawkitApp` 同样偏大，混合了装配、REPL、slash command、审批 UI、observability 查看、session、MCP、skill、IM、格式化输出和工具注册。

证据：

- 类入口：`clawkit-cli/src/main/java/com/clawkit/cli/ClawkitApp.java:69`
- 依赖装配和 engine 创建：`ClawkitApp.java:139`, `ClawkitApp.java:176`
- 工具 UI 回调：`ClawkitApp.java:237-245`
- REPL 主循环：`ClawkitApp.java:258`
- slash command switch：`ClawkitApp.java:299-341`
- approval UI：`ClawkitApp.java:384`
- observability 命令：`ClawkitApp.java:515`, `ClawkitApp.java:539`, `ClawkitApp.java:576`
- session/MCP/skill 命令：`ClawkitApp.java:861`, `ClawkitApp.java:997`, `ClawkitApp.java:1076`
- 工具注册：`ClawkitApp.java:1526-1537`

判断：CLI 拆分很有必要，但不应排在 `ToolCallExecutor` 前。原因是 CLI 主要是外壳，当前真正影响安全和可观测的路径在 engine/tools。

### 工具契约

当前工具契约仍是旧模型：

- `Tool.execute(String) -> Result<String>`
- `Tool.isReadOnly()` 只有布尔值。
- `ToolDefinition` 只有 name/description/schema。
- `ToolResult` 只有 output/isError。
- 风险等级在 engine 的 `ApprovalRequest` 里按工具名硬编码。

证据：

- `Tool`：`clawkit-tools/src/main/java/com/clawkit/tools/Tool.java:7`, `Tool.java:22`
- `ToolDefinition`：`clawkit-tools/src/main/java/com/clawkit/tools/schema/ToolDefinition.java:6`
- `ToolResult`：`clawkit-tools/src/main/java/com/clawkit/tools/schema/ToolResult.java:7`
- `Registry.execute`：`clawkit-tools/src/main/java/com/clawkit/tools/Registry.java:17`
- `ToolRegistry.execute`：`clawkit-tools/src/main/java/com/clawkit/tools/ToolRegistry.java:62`
- 只读判断：`ToolRegistry.java:44-46`
- 审批风险硬编码：`clawkit-engine/src/main/java/com/clawkit/engine/ApprovalRequest.java:18-34`

判断：TODO 中的 `ToolMetadata`、`ToolExecutionRequest`、`ToolExecutionResult` 是 P0-R 的核心前置。建议先以兼容方式新增，不立刻删除旧接口：

- `Tool.metadata()` default 从旧字段推导。
- `ToolRegistry` 继续支持旧 `execute(ToolCall)`。
- `ToolCallExecutor` 使用新 request/result，但内部先适配旧 `Tool.execute(String)`。

### Observability

observability 模型已经有雏形，但运行事件未完全贯通：

- `RunEvent` 定义了 `TurnCompleted`、`ProviderCallCompleted`、`ToolCompleted`、`CompactCompleted`、`ApprovalDecision`。
- `FileRunRecorder` 能消费这些事件。
- `AgentEngine` 当前主要发 `RunStarted`、`TurnStarted`、`RunCompleted`。
- `fireToolStart/fireToolEnd` 只给 CLI UI listener，不发 `RunEvent.ToolInvoked/ToolCompleted`。
- `ObservableLLMProvider` 存在，但 CLI 装配没有使用它。
- `totalToolFailures` 在 `run()` 内初始化并进入 summary，但工具执行路径没有更新它。

证据：

- `RunEvent` 事件类型：`clawkit-observability/src/main/java/com/clawkit/observability/RunEvent.java:51-94`
- `FileRunRecorder` 消费 tool/compact/provider 事件：`clawkit-observability/src/main/java/com/clawkit/observability/FileRunRecorder.java:101-119`
- `AgentEngine` 发 run/turn 事件：`AgentEngine.java:509`, `AgentEngine.java:545`, `AgentEngine.java:820`
- `AgentEngine` 工具 UI 事件：`AgentEngine.java:2001-2018`
- `ObservableLLMProvider`：`clawkit-observability/src/main/java/com/clawkit/observability/ObservableLLMProvider.java:19`
- CLI 装配 provider 直接来自 factory：`ClawkitApp.java:136`

判断：这不是阻断编译的问题，但会阻断 P0-R 的“可量化回归”。建议先补一个很薄的事件桥：

- 在 `ToolCallExecutor` 落地前，先让现有执行路径发 `ToolInvoked/ToolCompleted`。
- compact 分支发 `CompactTriggered/CompactCompleted`。
- approval 分支发 `ApprovalDecision`。
- provider 调用由 engine 包装或通过 request-scoped listener 记录，不能让 `ObservableLLMProvider` 生成 `runId=null, turnNumber=0` 的孤立指标。

### ContextPipeline

运行时上下文和持久会话已部分分离：

- `workspaceContext` / `memoryContext` / `runtimeContext` 三个 ephemeral 容器已存在。
- `assembleModelContext()` 负责把 ephemeral + sessionHistory 拼给 provider。
- `filterPersistable()` 过滤 runtime system message。

证据：

- ephemeral 容器：`AgentEngine.java:442-444`
- context 组装：`AgentEngine.java:1536-1542`
- 持久化过滤：`AgentEngine.java:1560-1563`
- clearSession 清理 ephemeral：`AgentEngine.java:1150-1158`
- autoSave 过滤：`AgentEngine.java:1362-1366`

判断：这部分已完成一半，但仍是 `AgentEngine` 私有方法和字段。`ContextPipeline` 不应只是把方法搬走，应该先明确输入输出模型：

- 输入：`SessionHistory`、`RuntimeContext`、`MemoryContext`、`WorkspaceContext`、`SkillContext`、`ToolDefinitions`、`ContextBudgetPolicy`。
- 输出：`ModelContext`、`PersistableHistory`、`ContextBudgetReport`、`CompactionReport`。

### MCP 风险与审计

MCP adapter 目前风险模型不足：

- `McpToolDef` 只有 name/description/inputSchema。
- `McpToolAdapter.isReadOnly()` 永远返回 false。
- 没有 `riskLevel`、`destructive`、`requiresApproval`。
- 审计日志手拼 JSON，且 `writeEntry()` 生成的 JSON 结构不合法：detail 被拼成一个没有值的字段名。

证据：

- `McpToolDef`：`clawkit-tools/src/main/java/com/clawkit/tools/mcp/McpToolDef.java:6`
- `McpToolAdapter.isReadOnly()`：`clawkit-tools/src/main/java/com/clawkit/tools/mcp/McpToolAdapter.java:46`
- `McpAuditLogger.logCall/logResult`：`clawkit-tools/src/main/java/com/clawkit/tools/mcp/McpAuditLogger.java:30-34`
- 手拼 JSON：`McpAuditLogger.java:37-40`

判断：MCP 不应该默认低风险；未知风险工具应保守处理。建议在工具契约升级后，把 MCP metadata 映射到统一 `ToolMetadata`，缺失字段时使用：

- `readOnly=false`
- `riskLevel=MEDIUM` 或 `HIGH`，取决于 schema/名称启发式
- `requiresApproval=true`

### BashTool 生命周期

当前 BashTool 已有 timeout、workDir 绑定和输出截断，但还不满足 TODO P0-R 验收：

- 使用 `redirectErrorStream(true)`，stdout/stderr 混合。
- `waitFor()` 后才 `readAllBytes()`，长输出可能阻塞子进程。
- 超时后 `destroyForcibly()`，但没有等待进程真正退出。
- 不返回 exit code。
- 输出截断只保留 head，不保留 head/tail。
- 缺少自动化测试覆盖长输出、stderr、超时、非零退出码、无输出。

证据：

- `BashTool`：`clawkit-tools/src/main/java/com/clawkit/tools/impl/BashTool.java:19`
- stderr 合并：`BashTool.java:136`
- wait 后读输出：`BashTool.java:149-154`
- head-only 截断：`BashTool.java:185-191`
- BashTool 测试缺口：当前 `rg BashTool` 只命中 manual demo，没有 `BashToolTest`。

判断：BashTool 修复应在 `ToolExecutionResult` 之后做，因为 exitCode、stderr、timedOut、truncated、outputBytes 都应该进入统一结果模型和 metrics。

### Provider 输出协议

Provider 当前接口直接返回 `Message`，OpenAI 具体解析和内部响应结构混在 `OpenAIProvider` 中：

- `LLMProvider.generate()` 返回 `Message`。
- `LLMException` 只有 message/cause，没有错误分类对象。
- `OpenAIProvider` 内部完成 HTTP、重试、SSE、tool call parser、错误解析。
- SSE 中 JSON 行解析失败会 `continue`，工具参数解析失败会降级为空对象。

证据：

- `LLMProvider`：`clawkit-provider/src/main/java/com/clawkit/provider/LLMProvider.java:17`
- `LLMException`：`clawkit-provider/src/main/java/com/clawkit/provider/LLMException.java:7`
- `OpenAIProvider`：`clawkit-provider/src/main/java/com/clawkit/provider/impl/openai/OpenAIProvider.java:33`
- SSE parser：`OpenAIProvider.java:205-291`
- 非流式响应转换：`OpenAIProvider.java:382-404`
- retry：`OpenAIProvider.java:412-464`

判断：Provider 协议收敛是必要的，但建议排在 tools/context/CLI 之后。原因是它会牵动模型通信和流式输出，行为风险较高。第一步可以先抽 parser 测试，不改变 `LLMProvider` 公共接口。

## 测试与验证现状

已执行命令：

```powershell
mvn -q -DskipTests compile
mvn -q -pl clawkit-tools '-Dtest=CommandSafetyInterceptorTest,McpSchemaSanitizerTest' test
mvn -q -pl clawkit-engine -am '-Dtest=PermissionModeTest,AgentEngineTest,SubAgentTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -q -pl clawkit-cli -am '-Dtest=ClawkitAppTest,ClawkitCompleterTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：

- 编译通过。
- tools 定向测试通过。
- engine 权限/主循环/subagent 定向测试通过。
- cli 静态命令测试通过，但 logback 在测试环境尝试写 `~/.clawkit/logs/clawkit.log`，出现权限拒绝噪声。
- 全量 `mvn -q test` 未完成，在超时时间内卡在 provider retry 等较慢测试路径；不能作为本次全量通过信号。

关键测试缺口：

- 没有 `ToolCallExecutor` 组件测试。
- 没有 `ContextPipeline` 独立测试。
- 没有 BashTool 生命周期测试。
- 没有 MCP 风险元数据/未知风险工具测试。
- 没有 MCP audit JSONL 可被 Jackson 逐行读取的测试。
- 没有 observability 事件贯通测试。
- CLI slash command 主要测试静态解析，缺少“slash command 不调 LLM”的 router 级测试。

## 推荐落地方案

### Step 0：补齐重构护栏和观测事件

目标：让 P0-R 后续每步重构都有可靠回归信号。

建议新增/补强：

- `AgentEngineObservabilityTest`
  - 一次工具成功应产生 `RunStarted`、`TurnStarted`、`ToolInvoked`、`ToolCompleted`、`RunCompleted`。
  - 工具失败应计入 `toolFailures`。
  - compact 触发时产生 compact event。
  - ASK 审批 approve/reject 产生 approval event。
- `SlashCommandRouterTest`
  - `/context`、`/runs`、`/metrics`、`/trace` 不调用 LLM。
- `McpAuditLoggerTest`
  - 成功/失败记录均为合法 JSONL。

验收：以上测试先在现有实现上通过或暴露明确缺口，再进入 Step 1。

### Step 1：新增工具元数据兼容层

建议在 `clawkit-tools` 新增：

- `ToolMetadata`
  - `name`
  - `description`
  - `inputSchema`
  - `readOnly`
  - `riskLevel`
  - `destructive`
  - `requiresApproval`
  - `sideEffectDescription`
- `ToolExecutionRequest`
  - `toolCallId`
  - `toolName`
  - `arguments`
  - `workDir`
  - `permissionMode`
  - `runId`
  - `turnNumber`
- `ToolExecutionResult`
  - `toolCallId`
  - `toolName`
  - `success`
  - `output`
  - `errorCode`
  - `errorMessage`
  - `durationMs`
  - `outputBytes`
  - `truncated`
  - `approved`
  - `approvalDecision`

兼容策略：

- `Tool` 增加 default `metadata()`，由旧方法推导。
- `ToolRegistry.getAvailableTools()` 继续返回现有 `ToolDefinition`，避免一次性改 provider。
- 老 `Result<String>` 先由 adapter 包装成 `ToolExecutionResult`。

### Step 2：抽出 ToolCallExecutor

职责：

- 根据 metadata 和 permission mode 判断是否允许执行。
- ASK 模式统一创建 `ApprovalRequest`。
- read-only 并行、write/high-risk 串行。
- 执行计时、输出大小、截断标记。
- 发 RunEvent。
- 生成 `Message.toolResult()`。
- todo sync/track 先可以保留为 hook，后续再迁移。

不做：

- 不同时改工具具体实现。
- 不重写 Provider 协议。
- 不改 CLI 展示格式。

验收：

- `AgentEngine` 不再直接调用 `registry.execute()`。
- 新增工具不需要修改 `AgentEngine` 主循环。
- `PermissionModeTest` 继续通过。
- 新增 `ToolCallExecutorTest` 覆盖 PLAN/ASK/AUTO、读并行、写串行、工具失败 metrics。

### Step 3：抽出 ContextPipeline

职责：

- 接收 session history 和各类 ephemeral context。
- 组装 model context。
- 应用 always-on rules。
- 执行 mask、budget analyze、compact。
- 返回可持久化 history 和 report。

验收：

- `AgentEngine` 不再直接操作 context 预算细节。
- runtime/memory/workspace context 不落盘的测试独立于主循环。
- compact 后关键约束保留测试继续通过。

### Step 4：MCP 风险模型和审计

职责：

- 扩展 `McpToolDef` 或额外 metadata 推导。
- `McpToolAdapter.metadata()` 给出保守风险。
- `McpAuditLogger` 使用 Jackson 写 JSONL。
- 审计字段脱敏并限制长度。

验收：

- 只读 MCP 工具可在 PLAN 模式暴露。
- 未知风险 MCP 工具默认需要审批。
- 审计日志可被 Jackson 逐行读取。

### Step 5：BashTool 生命周期

职责：

- stdout/stderr 并发 drain。
- 保留 exit code。
- timeout 后 destroy + wait。
- 截断保留 head/tail。
- `ToolExecutionResult` 标记 timedOut/truncated/outputBytes。

验收：

- 长输出不阻塞。
- stderr 独立可见。
- 超时命令被终止并返回明确错误。
- 非零退出码进入结构化结果。
- 无输出命令行为稳定。

### Step 6：CLI 拆分

建议拆：

- `ReplLoop`
- `SlashCommandRouter`
- `ApprovalConsole`
- `ConsoleRenderer`
- `RunCommandPrinter`
- `McpCommandHandler`
- `SkillCommandHandler`
- `SessionCommandHandler`

验收：

- `ClawkitApp` 只做依赖装配和启动。
- slash command 可独立单测。
- IM 开关不影响普通 CLI。
- 测试不再需要触发真实 logback 文件写入。

### Step 7：Provider 输出协议

建议先抽：

- `ModelResponse`
- `ModelToolCall`
- `ProviderError`
- `OpenAIToolCallParser`
- `OpenAIStreamParser`

兼容策略：

- 第一阶段 `LLMProvider` 仍返回 `Message`。
- OpenAI parser 先独立测试。
- 第二阶段再考虑 `LLMProvider.generate()` 返回 `ModelResponse`。

验收：

- provider 层只负责模型通信和协议解析。
- engine 只消费结构化 response。
- 协议错误不会进入工具执行层。

## 决策建议

近期最小可执行 PR 切分：

1. PR-1：observability 事件贯通 + 测试。
2. PR-2：`ToolMetadata` / `ToolExecutionRequest` / `ToolExecutionResult` 兼容模型。
3. PR-3：`ToolCallExecutor` 迁移普通工具执行路径。
4. PR-4：迁移 engine-internal 工具 hook：task/session_context/skill/memory/todo。
5. PR-5：`ContextPipeline` 独立组件。
6. PR-6：MCP metadata + audit JSONL。
7. PR-7：BashTool 生命周期。
8. PR-8：CLI router 拆分。
9. PR-9：Provider parser 抽离。

不建议一个 PR 同时做 `AgentEngine` 拆分、工具契约、MCP 风险和 CLI 拆分。这样会让行为变化和结构变化混在一起，难以回滚。

## 需要后续确认的问题

- `RiskLevel` 应放在 `clawkit-tools` 还是 `clawkit-engine`：如果 metadata 在 tools，风险枚举也应下沉到 tools 或新建 shared contract，避免 tools 依赖 engine。
- observability 是否应被 CLI 显式依赖：当前 CLI 通过 engine 的传递依赖使用 observability 类型，长期建议在 `clawkit-cli/pom.xml` 显式声明。
- `todo_write`、`remember`、`memory_save` 这类 engine-internal 工具是否统一进入 ToolCallExecutor，还是保留为 Engine hooks。
- `PlanExecutor` 当前直接 `registry.execute()`，后续是否也走 ToolCallExecutor，以保证权限/审计/metrics 一致。
