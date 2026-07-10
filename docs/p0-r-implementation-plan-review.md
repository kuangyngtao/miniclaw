# P0-R 底层结构重构完整技术方案与方案评审

日期：2026-07-11  
范围：`AgentEngine`、工具执行契约、上下文管线、MCP 风险和审计、Bash 生命周期、CLI 拆分、Provider 输出协议。  
结论：可以按本文 PR 顺序启动，但不得跳过 PR-0 护栏测试和观测贯通；`ToolCallExecutor` 合并前必须覆盖普通工具、PlanExecutor、SubAgent 和 engine-internal tools 的一致审计路径。

## 1. 设计目标

P0-R 的目标不是一次性重写 Agent，而是把已经存在的职责边界显式化，并用行为测试证明重构前后外部行为不倒退：

- `AgentEngine` 保留主循环编排，逐步移出工具执行、上下文组装、记忆 hooks、skill runtime、plan runtime。
- `ToolCallExecutor` 成为所有工具调用的唯一执行入口，统一审批、串并行、事件、metrics、审计和结果回注。
- `ContextPipeline` 明确哪些消息参与本轮模型上下文，哪些可以持久化到 session。
- MCP、Bash、Provider 的风险、输出和错误不再靠字符串约定传递。
- CLI 拆成装配、输入循环、命令路由、审批 UI、展示渲染，避免继续把核心规则沉在 `ClawkitApp`。

非目标：

- 不在 P0-R 首个 PR 改 Provider 公共接口返回值。
- 不在同一个 PR 同时迁移工具契约、MCP 风险、CLI 路由和 Provider parser。
- 不引入数据库、Web dashboard 或大规模 eval 平台。
- 不删除旧接口，直到新路径被测试和 metrics 覆盖。

## 2. 代码对照

| 设计点 | 当前代码事实 | 方案约束 |
|---|---|---|
| `AgentEngine` 过重 | `AgentEngine` 主类在 `clawkit-engine/src/main/java/com/clawkit/engine/impl/AgentEngine.java:77`，主入口 `run()` 在 `:499`，plan-execute 入口在 `:1675`。 | 拆分必须保留 `AgentEngine` 门面，按职责迁移私有方法，不直接替换主循环。 |
| 运行时上下文已半分离 | `sessionHistory` 在 `AgentEngine.java:405`，`workspaceContext/memoryContext/runtimeContext` 在 `:442-444`，组装在 `assembleModelContext()` `:1536-1542`，持久化过滤在 `filterPersistable()` `:1560-1563`。 | `ContextPipeline` 先包装现有字段和顺序，再独立测试持久化边界。 |
| 普通工具执行散落 | 读工具并行入口 `executeParallel()` 在 `AgentEngine.java:865`，写工具串行入口 `executeSequential()` 在 `:908`，直接调用 `registry.execute()` 在 `:877` 和 `:977`。 | `ToolCallExecutor` 先替换普通工具路径，保留行为兼容。 |
| internal tools 绕过 registry | `executeSequential()` 中 `task/session_context/skill_load/skill_unload/memory_save/remember` 等 engine-internal tools 在 `AgentEngine.java:910-975` 之间分支处理。 | internal tools 必须纳入 executor 的事件和结果模型，否则 trace 仍不完整。 |
| SubAgent 单独创建子引擎 | `spawnSubAgent()` 在 `AgentEngine.java:1024`，子引擎创建在 `:1054-1060`，实际执行 `subEngine.run()` 在 `:1067`。 | 子 run 需要独立 runId 和 recorder，并在父 trace 中记录引用。 |
| PlanExecutor 绕过 engine 工具链 | `PlanExecutor.execute()` 在 `clawkit-engine/src/main/java/com/clawkit/engine/impl/PlanExecutor.java:40`，直接 `registry.execute()` 在 `:208` 和 `:263`。 | plan-execute 不能长期绕过权限、审计和 metrics。 |
| 工具契约过薄 | `Tool` 只有 `execute(String)` 和 `isReadOnly()`，见 `clawkit-tools/src/main/java/com/clawkit/tools/Tool.java:7-22`；`ToolDefinition` 仅 name/description/schema，见 `clawkit-tools/src/main/java/com/clawkit/tools/schema/ToolDefinition.java:6-10`；`ToolResult` 仅 output/isError，见 `clawkit-tools/src/main/java/com/clawkit/tools/schema/ToolResult.java:7-10`。 | 新契约放在 `clawkit-tools`，不得引用 engine 类型。旧接口保留默认适配。 |
| 风险等级位置不利于下沉 | 当前 `RiskLevel` 在 `clawkit-engine/src/main/java/com/clawkit/engine/RiskLevel.java:3`，但 `ToolMetadata` 需要定义在 tools。 | 新增 `ToolRiskLevel` 到 tools；engine/CLI 可迁移或做短期映射。 |
| 观测事件有模型但未贯通 | `RunEvent` 包含 provider/tool/approval 事件，见 `clawkit-observability/src/main/java/com/clawkit/observability/RunEvent.java:16`、`:57`、`:74`、`:94`；`fireRunEvent()` 在 `AgentEngine.java:2035`。 | executor 和 provider invoker 必须统一发 `RunEvent`，不能只发 UI listener。 |
| `fireToolStart/End` 只服务 UI | `fireToolStart()` 在 `AgentEngine.java:2001`，`fireRunEvent()` 独立在 `:2035`。 | 工具开始/结束事件需要同时驱动 CLI UI 和 JSONL recorder。 |
| CLI 混合装配与交互 | `ClawkitApp` 主类在 `clawkit-cli/src/main/java/com/clawkit/cli/ClawkitApp.java:69`，provider 装配在 `:136`，registry 装配在 `:139` 和 `createToolRegistry()` `:1526`，REPL loop 在 `:258`，slash switch 在 `:299-341`，recorder 装配在 `:189-191`。 | CLI 拆分排在 core runtime 之后，先保持行为，再拆 router/renderer。 |
| CLI 隐式依赖 observability | `clawkit-cli/pom.xml:17-21` 只显式依赖 engine；engine 显式依赖 observability 在 `clawkit-engine/pom.xml:37-41`，但 CLI 代码直接使用 observability 类型。 | 后续需显式声明依赖或引入 engine facade，避免传递依赖成为公共契约。 |
| MCP 风险模型粗糙 | `McpToolDef` 仅 name/description/schema，见 `clawkit-tools/src/main/java/com/clawkit/tools/mcp/McpToolDef.java:6`；`McpToolAdapter.isReadOnly()` 固定 false，见 `McpToolAdapter.java:46`。 | MCP metadata 缺失时保守处理，未知风险默认需要审批。 |
| MCP audit 不是合法 JSONL | `McpAuditLogger.writeEntry()` 手拼 JSON 在 `clawkit-tools/src/main/java/com/clawkit/tools/mcp/McpAuditLogger.java:37-40`。 | 用 Jackson 写 `McpAuditRecord`，逐行可解析。 |
| Bash 生命周期有阻塞风险 | `BashTool` 在 `clawkit-tools/src/main/java/com/clawkit/tools/impl/BashTool.java:19`；stderr 合并在 `:136`；`waitFor()` 后 `readAllBytes()` 在 `:149-154`；截断只保留头部在 `:185-190`。 | 改为并发 drain stdout/stderr，结果带 exitCode/timedOut/truncated/outputBytes。 |
| Provider 通信与解析耦合 | `LLMProvider.generate()` 返回 `Message`，见 `clawkit-provider/src/main/java/com/clawkit/provider/LLMProvider.java:17-25`；`OpenAIProvider` HTTP、SSE、parser、retry 混在 `clawkit-provider/src/main/java/com/clawkit/provider/impl/openai/OpenAIProvider.java:33`，SSE 工具参数累积在 `:260-286`，非流式转换在 `:382-399`，retry 在 `:412-464`。 | 先抽 parser 和测试，后续再引入 `ModelResponse`。 |

## 3. 目标架构

### 3.1 模块边界

`DESIGN.md` 要求 `tools` 不能依赖 `engine/cli/im`，`engine` 不包含终端 UI 和具体 Provider JSON 细节，`cli` 只做入口和展示。这决定了 P0-R 的接口放置：

- `clawkit-tools`：`ToolMetadata`、`ToolRiskLevel`、`ToolExecutionRequest`、`ToolExecutionResult`、`ToolSideEffect`、旧 `Tool` 默认适配。
- `clawkit-engine`：`ToolCallExecutor`、`ToolExecutionContext`、`InternalToolRouter`、`ProviderInvoker`、`ContextPipeline`、`PlanRuntime`、`MemoryHooks`。
- `clawkit-observability`：只保留事件和 recorder；不承载业务判断。
- `clawkit-cli`：`ClawkitApp`、`ApplicationBootstrap`、`ReplLoop`、`SlashCommandRouter`、`ApprovalConsole`、`ConsoleRenderer`。
- `clawkit-provider`：`OpenAIResponseParser`、`OpenAIStreamParser`、`ProviderError`，第一阶段不改 `LLMProvider` 返回类型。

### 3.2 工具契约

新增 tools 侧结构：

```java
public enum ToolRiskLevel { LOW, MEDIUM, HIGH }

public record ToolMetadata(
    String name,
    String description,
    Object inputSchema,
    boolean readOnly,
    ToolRiskLevel riskLevel,
    boolean destructive,
    boolean requiresApproval,
    Set<ToolSideEffect> sideEffects
) {}

public record ToolExecutionRequest(
    String toolCallId,
    String toolName,
    JsonNode arguments,
    Instant requestedAt,
    Map<String, String> attributes
) {}

public record ToolExecutionResult(
    String toolCallId,
    String toolName,
    String output,
    boolean error,
    String errorCode,
    long durationMs,
    int outputBytes,
    boolean truncated,
    boolean timedOut,
    Integer exitCode,
    ToolMetadata metadata
) {}
```

兼容策略：

- `Tool.execute(String)` 保留；`Tool` 新增 `default metadata()` 和 `default execute(ToolExecutionRequest)`。
- `ToolRegistry.execute(ToolCall)` 保留；新增 `execute(ToolExecutionRequest)` 和 `metadata(String toolName)`。
- `ToolDefinition` 不增加字段，避免影响 provider 请求结构；由 `ToolMetadata` 投影生成。
- `RiskLevel` 从 engine 迁移为 tools 侧 `ToolRiskLevel`；engine 的 `ApprovalRequest` 短期可映射，最终直接使用 tools enum。

### 3.3 ToolCallExecutor

目标职责：

- 根据工具 metadata 决定并行或串行。
- 根据 `PermissionMode`、`ApprovalHandler`、`requiresApproval`、`riskLevel` 执行审批。
- 执行前发 `RunEvent.ToolInvoked`，执行后发 `RunEvent.ToolCompleted`。
- 生成 `Message.toolResult()` 并回注到上下文。
- 统一统计 duration、outputBytes、truncated、timedOut、exitCode、errorCode。
- 对 MCP、Bash、写文件等副作用工具写审计。

建议 API：

```java
public final class ToolCallExecutor {
    public ToolExecutionBatchResult executeBatch(
        List<ToolCall> calls,
        ToolExecutionContext context
    );
}

public record ToolExecutionContext(
    String runId,
    int turnNumber,
    PermissionMode permissionMode,
    boolean allowParallelReadOnly,
    ApprovalHandler approvalHandler,
    InternalToolRouter internalTools,
    Consumer<RunEvent> eventSink
) {}
```

迁移顺序：

1. 普通 registry 工具先走 executor，保留原 `executeParallel()` / `executeSequential()` 的外部行为。
2. engine-internal tools 注册到 `InternalToolRouter`，不再作为 `AgentEngine` 中的大段分支。
3. `PlanExecutor` 注入 executor，替换直接 `registry.execute()`。
4. SubAgent 的父任务分派也通过 executor 发事件；子引擎注册独立 recorder。

### 3.4 ContextPipeline

输入输出模型：

```java
public record ContextPipelineInput(
    List<Message> sessionHistory,
    List<Message> workspaceContext,
    List<Message> memoryContext,
    List<Message> runtimeContext,
    List<Message> skillContext,
    ContextBudgetPolicy budgetPolicy
) {}

public record ContextPipelineResult(
    List<Message> modelContext,
    List<Message> persistableSessionHistory,
    ContextBudgetReport budgetReport,
    Optional<CompactionResult> compaction
) {}
```

第一阶段不改变现有顺序：`memory -> workspace -> session -> runtime`，对应 `AgentEngine.java:1536-1542`。迁移完成后，`sniffWorkspaceState()`、`injectRelatedSessions()`、working memory 注入、runtime warning、compact 和 `filterPersistable()` 都由 pipeline 测试覆盖。

### 3.5 MCP 风险和审计

新增：

- `McpToolMetadataResolver`：把 MCP 返回的 schema/annotations/name pattern 映射到 `ToolMetadata`。
- `McpAuditRecord`：合法 JSONL，字段包含 ts、server、tool、action、argSummary、riskLevel、approval、outcome、durationMs、outputBytes、errorCode。
- `McpAuditLogger`：使用 Jackson `ObjectWriter` 写入；日志路径可注入，测试使用临时目录。

默认规则：

- 无 metadata 的 MCP 工具：`readOnly=false`、`riskLevel=HIGH`、`requiresApproval=true`。
- 明确只读的 MCP 工具：允许 PLAN 模式暴露，但仍记录审计。
- destructive 或未知写操作：ASK 下必须审批，AUTO 下仍走安全拦截和审计。

### 3.6 Bash 生命周期

新增 `ProcessRunner` 或 `CommandProcessRunner`：

- 不再 `redirectErrorStream(true)`，分别 drain stdout/stderr。
- 启动后立即并发读取 stdout/stderr，避免长输出阻塞。
- timeout 后先 destroy，再 destroyForcibly，并等待短时间回收。
- 输出截断保留 head/tail，并在 `ToolExecutionResult` 中标记 `truncated=true`。
- 结果包含 exitCode、timedOut、stdoutBytes、stderrBytes。

### 3.7 CLI 拆分

目标类：

- `ApplicationBootstrap`：读取配置、创建 provider/registry/engine/recorder。
- `ReplLoop`：只负责读取输入、处理 Ctrl+C、调用 router 或 engine。
- `SlashCommandRouter`：命令解析和 handler 分发。
- `ApprovalConsole`：审批框、输入解析、重试上限。
- `ConsoleRenderer`：banner、context、metrics、trace、plan 展示。

拆分原则：

- `ClawkitApp` 保留 picocli 入口和依赖装配。
- slash command handler 不直接调 LLM。
- IM 模式使用同一个 bootstrap，但不复用 REPL loop。
- observability 依赖显式化，或通过 engine facade 暴露 reader，不能继续依赖传递依赖。

### 3.8 Provider 输出协议

两阶段执行：

1. 抽出 `OpenAIResponseParser` 和 `OpenAIStreamParser`，保持 `LLMProvider.generate()` 返回 `Message`。
2. 在 parser 稳定后新增 `ModelResponse`，包含 message、toolCalls、finishReason、usage、rawProviderId、parseWarnings、providerError。

关键要求：

- 流式工具参数解析失败不能吞成 `{}` 后继续执行；需要返回 parse error 或 warning。
- retry 计数通过 provider hook 或 `ProviderInvoker` 传给 `RunEvent.ProviderCallCompleted`。
- engine 不直接依赖 OpenAI JSON 结构。

## 4. PR 切分

### PR-0：重构护栏和观测贯通

代码依据：`RunEvent` 已存在 provider/tool/approval 事件模型，但 `AgentEngine` 当前主要只发 run/turn/compact 事件；普通工具只调用 UI `fireToolStart()`，见 `AgentEngine.java:874`、`:976`、`:2001`。

实施：

- 补 `AgentEngineToolTraceTest`，验证普通工具成功/失败写入 trace。
- 补 `PermissionModeRegressionTest`，覆盖 PLAN 只读、ASK 审批、AUTO 安全拦截。
- 补 `RuntimeContextPersistenceTest`，验证 `[Runtime]`、`[Working Memory]` 不写入 session。
- 补 CLI router 级测试，验证 slash command 不触发 provider。
- 在现有路径补齐 `RunEvent.ToolInvoked/ToolCompleted/ApprovalDecision`，作为 executor 迁移前 baseline。

验收：

- `mvn -pl clawkit-engine,clawkit-cli,clawkit-tools test` 通过。
- 一次普通工具调用 run 生成 trace，包含 tool invoked/completed。
- 评审遗留的 P0-O 观测项不再被 P0-R 放大。

### PR-1：tools 侧兼容契约

代码依据：`Tool.java:7-22`、`ToolRegistry.java:44-62`、`ToolDefinition.java:6-10`、`ToolResult.java:7-10`。

实施：

- 新增 `ToolMetadata`、`ToolRiskLevel`、`ToolExecutionRequest`、`ToolExecutionResult`。
- `Tool` 增加默认适配方法；所有现有工具不强制一次性改签名。
- `ToolRegistry` 新增 `metadata()` 和 request/result 执行入口。
- `ToolDefinition` 保持 provider 投影，不承载 risk 字段。
- 增加兼容测试：旧 `execute(String)` 与新 `execute(ToolExecutionRequest)` 输出一致。

验收：

- tools 不依赖 engine。
- 现有工具注册和 provider tool schema 不变。
- 新结果模型可表示 `errorCode/outputBytes/truncated/timedOut/exitCode`。

### PR-2：ToolCallExecutor 迁移普通工具路径

代码依据：普通工具路径在 `AgentEngine.executeParallel()` `AgentEngine.java:865-904` 和 `executeSequential()` `:908-986`；审批逻辑也在 `executeSequential()`。

实施：

- 新增 `ToolCallExecutor` 和 `ToolExecutionContext`。
- `AgentEngine.run()` 传入 runId/turnNumber/permissionMode/eventSink。
- 并行只读和串行写工具由 executor 决定。
- executor 统一发 UI tool event 和 `RunEvent`。
- 保留 `AgentEngine` 原方法作为薄适配，或删除私有实现但保留测试行为。

验收：

- PLAN/ASK/AUTO 行为与旧路径一致。
- metrics 中所有普通工具有 duration/outputBytes/success/riskLevel。
- `AgentEngine` 不再直接调用普通工具的 `registry.execute()`。

### PR-3：internal tools、PlanExecutor、SubAgent 接入 executor

代码依据：engine-internal tools 分支在 `AgentEngine.java:910-975`；SubAgent 在 `AgentEngine.java:1024-1075`；PlanExecutor 直接调用 registry 在 `PlanExecutor.java:208` 和 `:263`。

实施：

- 新增 `InternalToolRouter`，把 task/session_context/skill/memory/remember/todo sync 包装为 internal tool handler。
- `PlanExecutor` 注入 `ToolCallExecutor` 或 `ToolExecutionPort`，替换直接 `registry.execute()`。
- SubAgent 父任务分派发完整 tool events；子引擎创建时注册独立 recorder 或 child event sink。
- 父 trace 中记录 childRunId、instruction summary、duration、turns、tokens。

验收：

- Plan-execute 中工具调用同样进入 trace/metrics。
- SubAgent 子 run 生成独立 run 目录，父 run 可关联子 run。
- internal tools 不再出现无 trace 的空洞。

### PR-4：ContextPipeline 独立组件

代码依据：ephemeral 容器在 `AgentEngine.java:442-444`，注入点在 `:517-521`、`:563`、`:573`、`:590`、`:600`，组装在 `:1536-1542`，过滤在 `:1560-1563`，compact 写回在 `:669-671` 和 `:2179-2189`。

实施：

- 新增 `ContextPipelineInput/Result` 和 `ContextPipeline`。
- 第一版只搬移 `assembleModelContext()`、`filterPersistable()`、compact 前后预算计算。
- 第二版再迁移 workspace/memory/runtime 注入方法。
- 保持 `AgentEngine` 字段，直到测试覆盖后再把 ephemeral state 封进 `EphemeralContext`。

验收：

- pipeline 独立测试覆盖上下文顺序、runtime 不持久化、compact 后持久化过滤。
- `/context` 输出不退化。
- 运行多轮不会重复累积 runtime/memory 注入消息。

### PR-5：MCP metadata 和 audit JSONL

代码依据：`McpToolDef.java:6` 元数据不足，`McpToolAdapter.java:46` 固定非只读，`McpAuditLogger.java:37-40` 手拼 JSON。

实施：

- MCP tool list 结果清洗为 `ToolMetadata`。
- 缺失 metadata 时默认高风险审批。
- 用 Jackson 写 `McpAuditRecord`。
- 审计路径可注入，单测不写真实用户目录。

验收：

- 只读 MCP 工具可被识别。
- 未知 MCP 工具默认需要审批。
- audit JSONL 可被 Jackson 逐行读取。

### PR-6：BashTool 生命周期

代码依据：`BashTool.java:136` 合并 stderr，`:149-154` wait 后读输出，`:185-190` 只保留头部。

实施：

- 新增 `ProcessRunner`，并发 drain stdout/stderr。
- `BashTool` 使用 `ProcessRunner`，输出摘要和完整结构进入 `ToolExecutionResult`。
- 截断策略改为 head/tail。
- 区分非零 exit、timeout、启动失败、中断。

验收：

- 长输出、stderr、超时、非零退出、无输出都覆盖。
- timeout 不留下子进程。
- metrics 显示 exitCode/timedOut/truncated。

### PR-7：CLI router / renderer 拆分

代码依据：`ClawkitApp.java:69` 主类、`:136-191` 装配、`:258` REPL、`:299-341` slash switch、`:1526` 工具注册。

实施：

- 抽 `SlashCommandRouter` 和 handler。
- 抽 `ApprovalConsole`。
- 抽 `ConsoleRenderer`。
- 抽 `ApplicationBootstrap`。
- `ClawkitApp` 只保留 picocli 参数和启动入口。

验收：

- slash command 单测不需要真实 provider。
- `/runs`、`/metrics`、`/trace` 行为不变。
- IM 开关不影响普通 CLI。

### PR-8：Provider parser 和 ModelResponse 收敛

代码依据：`LLMProvider.java:17-25` 返回 `Message`；`OpenAIProvider.java:260-286` 流式 parser，`:382-399` 非流式 parser，`:412-464` retry。

实施：

- 抽 parser，不改 `LLMProvider`。
- 流式和非流式 parser 共用 tool call 校验。
- parse error 不再吞成空参数继续执行。
- 增加 `ProviderError` 和 parser tests。
- 第二阶段引入 `ModelResponse`，engine 逐步消费。

验收：

- parser 单测覆盖 tool call、坏 JSON、空 choices、finish reason、usage。
- retry 次数进入 provider metrics。
- engine 不接触 OpenAI 原始 JSON。

## 5. 测试矩阵

| 区域 | 必测用例 |
|---|---|
| Engine 主循环 | 无工具结束、单工具成功、工具失败自愈、最大轮次、compact 触发、runtime context 不持久化。 |
| 权限 | PLAN 拦截写工具、ASK 审批 approve/reject/modify、AUTO 低风险自动执行但仍审计。 |
| ToolCallExecutor | 读工具并行、写工具串行、混合工具串行、internal tool、PlanExecutor、SubAgent 父子 trace。 |
| tools 兼容 | 每个内置工具旧入口和新入口一致；错误码和 outputBytes 正确。 |
| MCP | metadata 映射、未知风险默认高风险、audit JSONL 成功/失败结构一致。 |
| Bash | stdout/stderr 大输出、timeout、非零 exit、无输出、启动失败、中断。 |
| ContextPipeline | 顺序、预算、compact、persistable filter、ephemeral clear。 |
| CLI | slash command 不调 LLM、审批 UI 输入解析、metrics/trace/runs 展示。 |
| Provider | SSE tool call 累积、坏参数 parse error、非流式 tool call、retry/circuit breaker。 |

## 6. 回滚策略

- 每个 PR 保留旧入口或薄适配，直到下一 PR 完成后再删除旧实现。
- `ToolRegistry.execute(ToolCall)`、`Tool.execute(String)` 在整个 P0-R 期间保留。
- Provider parser 首 PR 不改 `LLMProvider` 签名。
- CLI 拆分前先有 router 单测，失败时可回退到 `ClawkitApp` 原 switch。
- MCP audit 新格式可以通过版本字段 `schemaVersion=1` 区分，不尝试迁移旧日志。

## 7. 方案评审

### 7.1 阻塞级问题

1. `ToolMetadata` 的风险等级不能复用 engine 里的 `RiskLevel`。  
   依据：tools 不得依赖 engine 的模块约束见 `DESIGN.md:57`；当前 `RiskLevel` 在 `clawkit-engine/src/main/java/com/clawkit/engine/RiskLevel.java:3`。如果 request/result 放入 tools 却引用 engine enum，会形成反向依赖。本文已要求新增 `ToolRiskLevel`。

2. `ToolCallExecutor` 不能只替换 `AgentEngine` 普通工具路径。  
   依据：PlanExecutor 在 `PlanExecutor.java:208`、`:263` 直接执行 registry；SubAgent 在 `AgentEngine.java:1054-1067` 创建子引擎；internal tools 在 `AgentEngine.java:910-975` 绕过 registry。如果这些路径不迁移，权限、审计和 metrics 仍不一致。

3. provider 观测不能只在 CLI 层包一层无上下文 decorator。  
   依据：CLI 当前直接 `ProviderFactory.create()` 在 `ClawkitApp.java:136`，`ObservableLLMProvider` 存在于 `clawkit-observability/src/main/java/com/clawkit/observability/ObservableLLMProvider.java:19`，但 provider events 需要 runId/turnNumber。应由 engine 的 `ProviderInvoker` 或 request-scoped hook 产生事件。

### 7.2 高风险点

- tools 契约迁移会影响所有内置工具和 MCP adapter；必须保留旧入口并加兼容测试。
- `ContextPipeline` 一旦改变消息顺序，会影响模型行为；第一阶段只包装现有顺序。
- SubAgent child run 如果复用父 runId，会污染 metrics；如果完全孤立又没有父 trace 引用，会失去可追踪性。
- Bash 并发 drain 跨 Windows/Linux shell 行为不同，测试必须覆盖 PowerShell/cmd 和类 Unix shell 策略。
- CLI 拆分容易造成展示回归，必须先锁定 `/runs`、`/metrics`、`/trace` 文本行为。

### 7.3 测试缺口

- 当前缺 `ToolCallExecutorTest`，无法证明权限、串并行、metrics 一致。
- 当前缺 `ContextPipelineTest`，无法证明 runtime context 不持久化边界。
- 当前缺 `BashToolTest` 覆盖长输出、stderr、超时、非零退出和无输出。
- 当前缺 MCP metadata / audit JSONL 测试。
- 当前缺 PlanExecutor 走统一工具执行路径的回归测试。
- 当前缺 SubAgent 父子 run trace 关联测试。

### 7.4 设计建议

- 先把观测事件贯通作为 PR-0，而不是等 executor 完成后再补。
- `ToolExecutionRequest` 只包含工具执行需要的数据；runId、turnNumber、permissionMode 放 engine 侧 `ToolExecutionContext`。
- `ToolDefinition` 保持 provider schema 投影，避免把 risk 字段发送给模型。
- `Provider ModelResponse` 放在最后，先抽 parser，降低模型通信回归风险。
- TODO 中 P0-R 任务保持高层路线，评审发现的具体问题单独追加为待办，避免路线图被长方案污染。

### 7.5 结论

修复并跟踪本文阻塞级问题后，方案可进入实施。推荐立即启动 PR-0 和 PR-1；PR-2 合并前必须证明普通工具 trace/metrics 不倒退；PR-3 完成前不得宣称 `ToolCallExecutor` 统一完成。
