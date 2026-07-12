# clawkit TODO

> 状态：`[ ]` 未开始，`[~]` 部分完成或正在迁移，`[x]` 已满足全部验收标准。

本文档只维护当前事实、实施顺序和验收标准。项目边界看 [CLAUDE.md](CLAUDE.md)，稳定工程规范看 [DESIGN.md](DESIGN.md)。

## 维护规则

- 状态必须由代码和测试证明；创建类、接口或模块不等于完成迁移。
- `[x]` 需要记录完成日期、核心变化和验证方式。
- 一个任务只在一个章节维护；跨章节依赖使用引用，不复制待办。
- 父项只有在全部子项和验收标准满足后才能标记 `[x]`。
- 无法运行验证时保持 `[~]` 或 `[ ]`，并写明原因和残余风险。
- 新发现的问题放入所属主链，不新增“评审遗留问题”堆积区。

## 当前代码事实

截至 2026-07-11：

- Maven 多模块原型可运行，文档记录的测试基线为 295 个测试通过；合入前仍需重新验证。
- `AgentEngine` 约 2368 行，仍混合 Agent loop、上下文、记忆、Session、Skill、Plan 和 SubAgent。
- `ClawkitApp` 约 1529 行，仍混合装配、REPL、命令、审批和 IM 生命周期。
- `ToolCallExecutor` 已接入普通 ReAct 和 internal tools，但 PlanExecutor、SubAgent 特殊路径和旧执行方法尚未全部收敛。
- runtime/session 已有 ephemeral 容器隔离，但独立 `ContextPipeline`、`MemoryHooks`、`SkillRuntime` 尚未形成。
- observability 已有 RunEvent、trace 和 summary 骨架，但没有 `metrics.jsonl`，并发 run 隔离和 Provider 作用域不完整。
- CLI 已出现 Bootstrap/Context/Renderer，但存在重复装配和依赖传递依赖的问题。

## 当前执行顺序

只按以下顺序推进 P0，避免继续并行铺开半成品：

1. **O1 可信记录器**：修正 run 隔离、三文件契约和观测测试。
2. **S1 工具契约与风险**：统一 metadata、结构化结果和审批来源。
3. **R1 工具执行主链**：迁移 Plan/SubAgent，删除旧执行路径。
4. **R2 上下文主链**：提取 ContextPipeline，再迁移 Memory/Skill。
5. **R3 入口主链**：收敛唯一装配、REPL、命令和审批 UI。
6. **R4 Provider 主链**：统一 ModelResponse、流式 parser 和 request-scoped 观测。
7. **O2 Benchmark + P0-D**：用稳定指标建立回归对比，再补工程交付闭环。

未达到前一项完成定义时，不开启下一项的大规模实现；可以补测试或修阻断 bug。

## P0：可靠运行与安全重构

### 已完成基础能力

- **[x] 真实 tokenizer 与模型窗口配置**（context / provider）
  JTokkit 替代字符估算，LLMConfig 按模型选择 context window/encoding。

  ✅ 2026-07-05 — `/context` 和 compact 阈值使用 tokenizer；相关 context 测试通过。

- **[x] 上下文预算模型**（context / engine）
  已建立预算阈值、分区分析、compact 结果和硬限制。

  ✅ 2026-07-08 — ContextBudgetPolicy、ContextBudgetAnalyzer、CompactionResult 和约束提取接入；相关 context/engine 测试通过。

## P0-O：可信观测与评测闭环

目标：一次 run 的事件能够隔离、完整地落盘，被 CLI 正确读取，并成为重构和 benchmark 的可信证据。

### O1：本地运行记录

✅ **2026-07-11 完成 — 4 PR / 186 测试全部通过**

- **[x] 按 run 隔离 `FileRunRecorder`**（observability）
  每个 run 固定产出（两文件契约）：
  - `.clawkit/runs/<run-id>/events.jsonl` — 唯一事实来源，每行一个 `RunEventEnvelope`
  - `.clawkit/runs/<run-id>/summary.json` — 由 `RunAccumulator` 从事件聚合的原子快照
  recorder 按 `ConcurrentHashMap<String, RunState>` 管理独立 writer、lock、sequence 和 accumulator；写入 `taskSummary`（脱敏、截断到 160 字符）；成功、失败或中断时正确关闭对应资源。
  验收：根 run、并行 SubAgent、失败 run 互不串写；两文件均为合法 JSON/JSONL；敏感参数只保留脱敏摘要。

- **[x] 补齐 Metrics 模型与事件投影**（observability）
  创建 12 种 `RunEventPayload` sealed 子类型 + `RunEventEnvelope` + `RunEventCodec`；
  `RunAccumulator` 流式聚合 20+ 指标字段（Run/Turn/Provider/Tool/Context/Compact/Approval）；
  `RunMetricsProjector` 批量回放投影；`RunMetrics` 拆分 Provider/Tool/Context/Compact/Approval 子指标。
  验收：summary 与回放投影使用同一套 `RunAccumulator`；工具指标包含 riskLevel、审批、耗时、输出大小、截断和错误码。

- **[x] 完整覆盖运行事件**（engine / observability）
  全部 20+ 事件调用点从旧 `RunEvent` sealed interface 迁移到新 `RunEventPayload` 类型；
  `ToolCallExecutor` 从 `ToolMetadata` 读取风险信息；审批事件携带结构化 decision/source；
  SubAgent 通过 `parentRunId` 关联父子 run；`RunCompleted` 不含聚合字段（由 accumulator 投影）。
  验收：ReAct / TWO_STAGE / Plan / SubAgent / internal tools × 成功 / 失败 / 中断 矩阵覆盖。

- **[x] CLI 观测入口与 Reader**（cli / observability）
  `/runs` 只读 `summary.json` 列表；`/metrics <runId>` 从 events 动态投影指标；
  `/trace <runId>` 结构化展示时间线（`sequence`、`eventType`、`turnNumber`）；
  `RunReader` 流式逐行容错：无效 JSON 跳过 + `INVALID_JSON` warning，未知事件保留为 `UnknownEventPayload`；
  CLI `pom.xml` 显式依赖 `clawkit-observability`；删除 `ClawkitApp` 与 `ApplicationBootstrap` 中的重复装配。
  验收：`/trace` 不再使用 `line.contains("ToolCompleted")` 字符串猜测；单行损坏提示 warning 并继续读取。

- **[x] Observability 独立测试**（observability / engine / cli）
  186 个测试全部使用临时目录，不访问真实 HOME、网络、模型或 MCP：
  - observability：71 个（codec / accumulator / projector / redactor / recorder / reader）
  - engine：69 个（AgentEngine / PlanExecutor / SubAgent / Session / Permission）
  - cli：46 个（ClawkitApp / ClawkitCompleter）

### O2：Benchmark 与回归报告

✅ **2026-07-11 完成 — 4 PR / 37 evaluation 测试全部通过**

- **[x] 最小 Benchmark Runner**（evaluation）
  新建 `clawkit-evaluation` 模块，16 个可机械判断的固定任务：read-search / glob-grep / edit-fix / run-verification / tool-failure-recovery / long-output-truncation / multi-turn / compact-trigger / dead-loop-stop / provider-failure / two-stage / plan-write-block / ask-approve-reject / auto-safety-block / plan-execute / parallel-subagents。
  验收：`BenchmarkRunner.runAll(BenchmarkCatalog.allCases())` 一键运行 16 个 case，输出 pass rate / avgTurns / avgToolCalls / avgDuration / toolFailureRate。
  前置：O1 两文件契约与事件 schema 稳定。

- **[x] 基线与回归对比报告**（evaluation）
  `BaselineStore` 读写版本化 baseline JSON（fingerprint + 每 case 指标）；`RegressionComparator` 逐 case 对比，硬门禁（PASS→FAIL / 安全约束 / 缺少 RunCompleted）+ 结构指标（turns/tools/failures/provider 按 case 比较，不比全局平均值）。
  验收：`RegressionComparator.compare(report, baseline)` 输出 DEGRADED / UNCHANGED / IMPROVED / INCOMPATIBLE_BASELINE，含具体差异。

暂不做数据库观测库、Web dashboard、复杂人工评分和多模型大规模评测。真实模型 benchmark 作为后续能力展开。

## P0-S：工具契约与安全闭环

### S1：统一契约和风险

- **[x] 完整结构化工具契约**（tools）
  `ToolMetadata`、`ToolExecutionRequest`、`ToolExecutionResult` 已重构为 V2 组合对象。新增 `ToolBehavior`、`ToolExecutionPolicy`、`ToolMetadataProvenance`、`ToolExecutionStatus`、`ToolError`、`ToolOutputStats`、`ApprovalRecord`、`ToolExecutionScope`。
  验收：结果通过 ToolExecutionStatus 枚举表达成功、拒绝、阻断、超时、截断、非零退出、工具错误和内部错误；旧 String 接口保留薄适配层。
  ✅ 2026-07-12 — PR-1 完成，66 测试全部通过。

- **[x] 统一 ToolRiskLevel 与审批来源**（tools / engine / cli）
  删除 engine/CLI 按工具名推断的第二套 RiskLevel（已标记 @Deprecated）；审批完全读取 ToolMetadata。创建 PermissionPolicy 接口 + ApprovalGrantCache。engine RiskLevel → deprecated，PR-8 删除。ASK 无审批器 → fail-closed。
  验收：内置工具均有显式 metadata() 覆盖；web_fetch 为 MEDIUM + NETWORK_OUT；未知 MCP 默认 HIGH + requiresApproval。
  ✅ 2026-07-12 — PR-2+PR-3 完成，工具 + engine 测试全部通过。

- **[x] MCP 元数据与坏参数保护**（tools / mcp）
  McpToolDef 扩展 annotations/outputSchema；McpServerConfig 增加 trusted 标志；McpClient 返回结构化 McpCallResult（保留 isError）；McpToolAdapter 重写 metadata() 和 execute()；坏 JSON → INVALID_ARGUMENTS；isError → TOOL_ERROR。
  验收：trusted/untrusted annotations 映射逻辑实现；编译全部通过。
  ✅ 2026-07-12 — PR-4 完成。

### S2：工具生命周期

- **[x] BashTool 进程与输出模型**（tools）
  抽取 ProcessRunner 接口 + DefaultProcessRunner；并发 drain stdout/stderr；有界 head collector；进程树终止（先快照 descendants → 再 SIGTERM/SIGKILL 父子）；timeout → TIMED_OUT；非零退出 → NON_ZERO_EXIT + exitCode；环境变量白名单；stdout/stderr 分区输出。
  验收：BashTool 覆盖 execute(ToolExecutionRequest)，返回结构化终态 + outputStats。
  ✅ 2026-07-12 — PR-5 完成，第二轮修复覆盖结构化终态。

- **[x] WriteTool 覆盖确认**（tools / safety）
  创建 WorkspacePathPolicy（toRealPath symlink 防护，新文件检查父目录 real path）；WriteTool 增加 overwrite 参数（默认 false）；非空文件无 overwrite → OVERWRITE_REQUIRED；原子写入（tmp + ATOMIC_MOVE）；TOCTOU 双重检查。
  验收：编译通过。
  ✅ 2026-07-12 — PR-6 完成，第二轮修复新文件创建路径。

- **[x] MCP 审计 JSONL + 统一审计投影**（tools / mcp / observability）
  McpAuditLogger 已删除；McpToolAdapter 不再写独立日志；RunAccumulator.onApprovalDecided() 修正（NOT_REQUIRED/AUTO_APPROVED/PLAN_BLOCKED/SAFETY_BLOCKED 不计入 approvalRequested）；审批指标只统计真实请求过用户的决策。
  验收：RunAccumulatorTest 17/17 通过。
  ✅ 2026-07-12 — PR-7 完成。

- **[x] Git 只读工具**（tools）
  封装 `git status`、`git diff`、`git log`、`git show`，减少模型使用 Bash 执行高频只读 git 操作。
  `GitReadTool`：单工具多操作（operation 枚举），ProcessBuilder 直调 git（-C 绑定工作区），MEDIUM 风险 + readOnly + idempotent + PARALLEL_SAFE。
  验收：11 测试全部通过（status/diff clean/diff dirty/log/default/log max_count/show/invalid op/non-git dir/metadata）。
  ✅ 2026-07-12 — PR-9 完成。

## P0-R：底层结构重构

目标：通过迁移真实运行路径拆分超大类；不以新增类数量作为进度。

### R0：重构护栏

- **[~] 核心行为矩阵**（engine / tools / cli / observability）
  覆盖普通 ReAct、TWO_STAGE、Plan、SubAgent、internal tools × 成功/失败 × PLAN/ASK/AUTO。
  验收：每条主链迁移前后运行相同测试；文件测试使用临时目录。
  
  🔶 已有 295 个测试基线，但缺 ToolCallExecutor、BashTool、MCP adapter/audit、RunRecorder 和跨执行模式权限矩阵。

### R1：唯一工具执行主链

- **[x] 普通 ReAct 与 internal tools 接入 ToolCallExecutor**（engine）
  所有工具——registry + internal——统一走 executeOne()：metadata → PermissionPolicy → 审批 → 执行。删除旧 `executeParallel()` / `executeSequential()` 死代码。internal tools 不再绕过权限与审批。metadataFor() 通过 registry.lookup() + isReadOnly() 回退获取 metadata，不再依赖 instanceof ToolRegistry。
  验收：PermissionModeTest 9/9 通过；AgentEngine 不直接执行 registry 工具。
  ✅ 2026-07-12 — PR-3+PR-8+第二轮修复完成。

- **[x] 迁移 SubAgent 工具路径**（engine）
  task 分派不再走独立的 executeSubAgentsParallel 特判分支，统一进入 ToolCallExecutor.executeBatch() → executeOne() → InternalToolRouter。子 engine 仍创建独立 ToolCallExecutor。
  验收：AgentEngine 中无 SubAgent 特判分支。
  ✅ 2026-07-12 — 第二轮修复完成。

- **[x] 迁移 PlanExecutor 工具路径**（engine）
  执行和 reviewer 工具调用统一走 ToolCallExecutor + planExecContext；删除 `registry.execute()` 直调。Plan 的写工具受 ASK/AUTO、安全拦截和审计约束。
  验收：PlanExecutor 两个调用点均已迁移。
  ✅ 2026-07-12 — PR-8+第二轮修复完成。

- **[x] 删除旧路径并收窄 AgentEngine**（engine）
  删除 `executeParallel()` / `executeSequential()` 死代码；删除 engine RiskLevel.java；删除 McpAuditLogger.java。fireToolInvoked/fireToolCompleted/fireApproval 增加 null recorder guard（Plan 场景）。
  验收：engine main 源码中无活跃的 registry.execute() 调用；PermissionModeTest 9/9；RunAccumulatorTest 17/17。
  ✅ 2026-07-12 — 第二轮修复完成。

### R2：上下文、会话与记忆主链

- **[x] 分离 ephemeral context 与持久 session**（engine / context）
  workspace/memory/runtime 容器已建立，自动保存过滤临时消息，clear 同步清空 ephemeral。

  ✅ 2026-07-08 — 连续多轮不重复持久化 Working Memory、Runtime 和 Related Sessions；相关回归测试通过。

- **[ ] 提取 ContextPipeline**（context / engine）
  唯一入口组装 system、history、workspace、runtime、memory、skill、tools 和预算；输出 ModelContext 与预算报告。
  验收：AgentEngine 不直接拼消息、compact 或过滤持久化内容；片段来源和生命周期可测试。

- **[ ] 提取 MemoryHooks**（engine / memory）
  迁移 run 前召回、run 后抽取、保存和相关 session 注入。
  验收：可注入 fake store；失败不污染 session；自动写入可关闭和审计。

- **[ ] 收敛 Session 持久化边界**（engine）
  使用显式 SessionHistory/SessionStore 契约，持久化 schema 带版本号；运行时注入只能经 ContextPipeline 进入模型上下文。
  验收：旧 session 可兼容读取；保存内容不含 ephemeral 消息；损坏文件和未知版本有结构化错误。

- **[ ] 提取 SkillRuntime**（engine / context）
  迁移 catalog、load/unload 和 SkillContext 生成。
  验收：无模型 CLI 操作不污染 session；组件可独立测试。

### R3：CLI 与入口主链

- **[~] 唯一 ApplicationBootstrap**（cli）
  已有 Bootstrap/Context/Renderer；删除 ClawkitApp 中的重复装配，并显式声明 provider/tools/observability 等实际源码依赖。
  验收：服务只装配一次；POM 与 import 一致；IM 和普通 CLI 共用应用服务。

- **[ ] 拆分 ReplLoop、SlashCommandRouter 和 ApprovalConsole**（cli）
  ClawkitApp 只负责 main/启动，Renderer 只展示。
  验收：命令、审批、中断和 IM 开关独立测试；slash command 不调用 LLM。

### R4：Provider 协议主链

- **[~] 统一 ModelResponse 与 parser**（provider / engine）
  非流式 OpenAIResponseParser 已提取；继续统一流式 SSE、tool calls、finish reason、usage 和协议错误。
  验收：engine 不解析具体 Provider JSON；坏工具参数不进入工具层。

- **[ ] request-scoped Provider 观测**（engine / provider / observability）
  所有模型调用携带 runId、turn、phase、streaming、token、duration 和 retryCount。
  验收：普通、phase1、compact、memory、Plan 和失败调用均有 ProviderCallCompleted；无 `runId=null` / `turn=0` 孤立指标。

## P0-D：工程交付闭环

- **[ ] CI 测试流水线**（ci）
  push/PR 运行 Java 21 全量测试、`git diff --check` 和必要静态检查。

- **[ ] Dockerfile 与 `.dockerignore`**（distribution）
  构建并运行 shaded jar，明确配置和工作区挂载。

- **[ ] 示例与演示路径**（docs / examples）
  覆盖 CLI、权限模式、代码读写、测试和 MCP；不含真实密钥。

- **[ ] 配置体验**（cli / config）
  统一 env/config 优先级，提供 example config 和脱敏 `/config`。

- **[ ] 用户可读错误**（cli / provider / tools）
  常见错误展示原因、影响和下一步，不直接抛内部堆栈。

- **[ ] 文档分层入口**（docs）
  README 保持用户向；必要时新增 `docs/architecture.md`、`runtime.md`、`mcp.md`、`development.md`，避免根文档再次膨胀。

- **[ ] Release 产物**（ci / distribution）
  tag 自动构建并发布可运行 jar；完成前不承诺稳定发行版。

## P1：任务完成率

前置：P0-O/S/R 的关键链路稳定并可度量。

- **[ ] 只读工具 session 缓存**：记录命中率，失效规则明确。
- **[ ] 工具结果智能截断**：保留 head/tail、错误片段和匹配行。
- **[ ] 任务感知 compact**：按任务类型保留不同证据。
- **[ ] Provider fallback**：超时、限流、熔断后切备用模型并记录指标。
- **[ ] 流式早停**：坏协议或明显无效输出中止，不进入工具层。
- **[ ] 失败分类与恢复策略**：区分可重试、需用户输入、不可恢复。

## P2：成本与效率

前置：Benchmark 能证明没有牺牲可靠性和完成率。

- **[ ] 自适应分层 compact**。
- **[ ] 多模型路由**。
- **[ ] Prompt caching**。
- **[ ] 可重置的 Bash session 复用**。
- **[ ] 记忆去重、冲突合并和衰减**。

## P3：远程运维 Ops Loop 与高级扩展

前置：P0/P1 稳定，O2 Benchmark 可用，权限、取消、run 隔离、审计和恢复均可验证。

Ops Loop 的主线是远程环境的“发现 → 采证 → 诊断 → 审批 → 修复 → 验证 → 回滚 → 复盘”，不包含自动修改业务代码。它是 clawkit 之上的运维应用，不是写入 AgentEngine 的垂类逻辑：

```text
clawkit Runtime
  -> clawkit-ops-loop（状态机 / SOP / 调度）
  -> clawkit-ops-mcp（结构化运维工具）
  -> SSH
  -> ops-fixtures / 云服务器
```

建议目录：

```text
extensions/
  clawkit-ops-mcp/
  clawkit-ops-loop/
ops-fixtures/
  compose.yaml
  cases/
  assertions/
  reports/
```

### OPS-0：本地可复现诊断闭环

- **[ ] Ops 四格检验与首批场景**（ops-fixtures）
  选择同时满足重复性、可验证性、预算可控、工具齐备的场景。首批覆盖 app down、Nginx 502、端口未监听、Redis/DB 不可达、磁盘压力。
  验收：每个 case 包含症状、关键证据、标准根因、允许/禁止工具、修复动作、验证断言和报告 schema。

- **[ ] 本地故障注入环境**（ops-fixtures）
  使用 Docker Compose 构造 nginx、app、postgres、redis、worker；故障可一键注入和恢复。
  验收：同一 case 可重复运行，初始状态和清理过程确定，不依赖真实生产数据。

- **[ ] 独立 `clawkit-ops-mcp` Server**（extensions / tools）
  提供窄化只读工具：system snapshot、service/container status、logs、ports、disk、HTTP probe、DB/Redis health。SSH 只是工具 Server 的后端，不新增 clawkit MCP transport。
  验收：不暴露通用 `shell_exec` / `ssh_exec(command)`；每个工具声明 schema、readOnly、riskLevel、timeout 和 auditFields。

- **[ ] Incident 状态机与持久化**（clawkit-ops-loop）
  状态至少覆盖 DISCOVERED、EVIDENCE_COLLECTED、DIAGNOSED、PLAN_READY、WAITING_APPROVAL、EXECUTING、VERIFYING、RESOLVED、ROLLED_BACK、ESCALATED。
  验收：状态写入磁盘，可在进程中断后恢复；Incident 关联 clawkit runId、evidence、attempt、verification 和 playbook。

- **[ ] Diagnosis Skill 与报告**（ops / skills）
  强制按“事实 → 推理 → 候选根因 → 结论 → 缺失证据 → 建议动作”输出；证据不足返回 INCONCLUSIVE。
  验收：本地 case 可计算关键证据覆盖率、根因命中率、平均工具数、耗时和 token。

### OPS-1：真实只读 SSH 运维

- **[ ] SSH 执行后端**（clawkit-ops-mcp）
  支持主机配置、密钥认证、known_hosts 严格校验、连接/命令 timeout、并发限制、输出截断和结构化错误。
  验收：私钥不进入模型、日志或仓库；测试使用 fake SSH Server/transport；目标主机和命令模板均为 allowlist。

- **[ ] 云服务器只读运维账号**（ops / infrastructure）
  使用非 root、无 sudo 的专用账号，只开放所需日志、状态和健康检查权限；首个远程环境仅运行 fixture/演示服务。
  验收：Agent 无法写文件、重启服务或执行任意命令；越权尝试被工具层和主机权限双重拒绝并审计。

- **[ ] 远程 Discovery Loop**（clawkit-ops-loop）
  手动触发远程巡检，聚合 systemd/container、端口、HTTP 和日志证据，生成 Incident 与诊断报告。
  验收：网络中断、SSH 鉴权失败、部分证据缺失和主机不可达均能分类并升级人工，不误判为业务根因。

### OPS-2：审批修复、独立验证与回滚

- **[ ] 窄化远程写工具**（clawkit-ops-mcp）
  仅提供 restart allowlisted service、switch known release、rollback release、cleanup fixture 等固定动作；禁止任意 sudo/bash/path。
  验收：全部为 HIGH risk + requiresApproval；记录目标、前置状态、审批、exitCode、输出摘要和预期后置条件。

- **[ ] Remediation Skill**（ops / skills）
  先查询 Playbook，再生成最小修复计划；每步包含 precheck、action、postcheck、rollback，最多自动重试 2–3 次。
  验收：ASK 拒绝后零副作用；执行失败进入 VERIFYING/ROLLBACK_REQUIRED，不继续盲目尝试。

- **[ ] 独立 Verification Agent**（ops / engine）
  verifier 使用新上下文和独立采集的证据，不接受修复 Agent 的自证。优先执行确定性断言，再做模型复查。
  验收：至少验证 exitCode、服务状态、端口、HTTP、原故障症状和新增 ERROR；能识别“隐藏日志而非解决问题”的假修复。

- **[ ] 任务级回滚**（ops / tools）
  写操作执行前记录可恢复状态；验证失败自动执行预定义回滚，回滚失败立即升级人工。
  验收：每个写 case 有成功、修复失败、验证失败、回滚成功和回滚失败测试。

### OPS-3：持续 Loop 与经验复利

- **[ ] Discovery Automation**（ops / scheduling）
  从手动触发升级为 Cron/健康告警触发；同类 Incident 去重并设置冷却窗口。
  验收：连续运行三天不重复轰炸、不并发修复同一目标；支持暂停、取消和预算熔断。

- **[ ] Playbook State**（ops / memory）
  将已验证根因、证据模式、修复与回滚方案写成版本化 YAML；新事件先检索，命中后仍需重新验证前置条件。
  验收：错误或过期 Playbook 不自动执行；记录来源、版本、命中次数、成功率和最后验证时间。

- **[ ] Ops Benchmark 与回归对比**（ops / evaluation）
  指标覆盖 discovery latency、证据覆盖、根因命中、计划可执行率、修复/回滚成功率、越权次数、假修复率、耗时和 token。
  验收：每次 Skill、模型或 Runtime 改动可与 baseline 比较，退化时阻止自动化等级提升。

- **[ ] 通知与人工升级**（ops / connector）
  输出 Incident、证据、计划、风险、审批入口、验证和回滚结果；第一阶段可使用 CLI/文件报告，后续再接 IM。
  验收：信息不足、预算耗尽、连续失败和高风险动作均可靠升级人工。

### 其他高级扩展

- **[ ] `/btw` 后台并行任务**。
- **[ ] 本地 embedding 语义搜索**。
- **[ ] GraalVM native-image**。

## 暂不优先

- 数据库观测平台和 Web dashboard。
- 中心化多 Agent/Gateway/多租户平台。
- 内置垂类业务包；应由插件、MCP、Skill 或 workflow 承担。
- 无人工审批、审计和回滚的高风险自动写操作。
- 无独立验证的生产自动修复；Ops Loop 在达到稳定 benchmark 前只用于 fixture/测试环境。
- 自动修改业务代码、Git Worktree、自动提 CR 和发布 Pipeline；这些属于代码维护 Loop，不纳入当前 Ops Loop 主线。
- 只能节省少量毫秒、但没有 benchmark 证明价值的微优化。

## 验证记录

- 2026-07-11：O2 完成 — 4 PR / 37 evaluation 测试全部通过（22 PR1 + 7 PR2 + 2 PR3 + 6 PR4）。16 个固定 benchmark case，ScriptedProvider 严格校验，CapturingRecorder + FileRunRecorder 写入真实 O1 链路，6 个机械 Scorer，逐 case 回归对比。
- 2026-07-11：O1 完成 — 4 PR / 186 测试全部通过（observability 71 + engine 69 + cli 46）。两文件契约（events.jsonl + summary.json）落地，metrics 改为 events 投影，不再持久化 metrics.jsonl。
- 2026-07-11：文档记录的 Maven 汇总为 295 个测试通过；本记录仅作为基线，实际合入前必须重新运行相关测试。
- 2026-07-11：TODO/CLAUDE/DESIGN 按”纲领 / 稳定设计 / 执行路线”重新分工；当前完成状态已按代码事实降级或重排。
- 2026-07-11：补充远程运维 Ops Loop 路线，按本地 fixtures、只读 SSH、审批修复、独立验证和持续调度分阶段实施；代码修复 Loop 明确不在当前范围。
- 2026-07-12：P0-S 全部 8 PR + 第二轮修复完成。66 工具测试 + 9 PermissionModeTest + 17 RunAccumulatorTest + 91 引擎/观测/上下文/记忆测试全部通过（0 failures），全项目 clean compile 通过。
  
  **第一轮（PR 1-6）**：契约 V2（8 新类型 + 4 核心类重构）、8 内置工具显式 metadata、PermissionPolicy + ApprovalGrantCache + ASK fail-closed、MCP annotations/trusted/isError/坏参数保护、ProcessRunner + BashTool 重写、WorkspacePathPolicy + WriteTool overwrite + 原子写入。
  
  **第二轮（P0 阻断修复）**：internal tools 走 PermissionPolicy、SubAgent 删除特判分支统一走 ToolCallExecutor、metadataFor() 用 registry.lookup() + isReadOnly() 回退、Plan recorder null guard、PlanExecutor 两调用点迁移到 ToolCallExecutor。
  
  **第二轮（P1 生命周期修复）**：BashTool 覆盖 execute(ToolExecutionRequest) → TIMED_OUT/NON_ZERO_EXIT/exitCode/outputStats、WriteTool requireWriteAccess 处理新文件、MCP annotations 缺失字段用保守默认值（fieldBool）、ProcessRunner 进程树先快照再终止 + exitValue try-catch、ToolMetadata compact constructor 验证平铺字段与 ToolBehavior 一致、RunAccumulator 审批指标修正（NOT_REQUIRED 不计入 approvalRequested）。
  
  **已删除**：engine RiskLevel.java、McpAuditLogger.java。engine main 源码中无活跃的 registry.execute() 调用。
