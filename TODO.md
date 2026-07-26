# clawkit TODO

> 状态：`[ ]` 未开始，`[~]` 部分完成或正在迁移，`[x]` 已满足全部验收标准。

本文档只维护当前事实、实施顺序和验收标准。项目边界看 [CLAUDE.md](CLAUDE.md)，稳定工程规范看 [DESIGN.md](DESIGN.md)，项目现状和技术亮点看 [docs/project-highlights-and-ops-loop-roadmap.md](docs/project-highlights-and-ops-loop-roadmap.md)，Ops 目标架构和门禁看 [docs/ops-loop.md](docs/ops-loop.md)。

## 维护规则

- 状态必须由代码和测试证明；创建类、接口或模块不等于完成迁移。
- `[x]` 需要记录完成日期、核心变化和验证方式。
- 一个任务只在一个章节维护；跨章节依赖使用引用，不复制待办。
- 父项只有在全部子项和验收标准满足后才能标记 `[x]`。
- 无法运行验证时保持 `[~]` 或 `[ ]`，并写明原因和残余风险。
- 新发现的问题放入所属主链，不新增“评审遗留问题”堆积区。

## 当前代码事实

截至 2026-07-18（P1-G 写操作前强制门禁完成）：

- 全量 `mvn -B -ntp clean verify` 通过：11 个 Reactor 模块、575 项测试，0 Failure、0 Error、0 Skipped；ArchUnit 10/10 硬规则通过，0 violations、0 frozen。
- 新增 `clawkit-reliability` 模块（可靠性内核）：CancellationTree、BudgetLedger、FailureDecisionTable、ActionAttemptCoordinator、FileActionAttemptStore（CRC journal + force + 跨进程文件锁事务）、SideEffectGate、DeterministicVerifier、RecoveryScanner。
- P1-G 七项门禁全部进入真实路径：取消/deadline/预算贯穿 ReAct、Plan、SubAgent、Provider、Tool、ProcessRunner；副作用工具必须生成 ActionDescriptor（无描述符 fail closed）；durable DISPATCH_INTENT 先于执行；结果未知 sticky 禁止自动重复写；MANUAL_REQUIRED 永不自动 VERIFIED_SUCCESS；进程启动恢复扫描 + reconcile；独立 Verification Run 隔离。定版设计与实现对照见 [docs/p1-g-design.md](docs/p1-g-design.md)。
- 四条主链继续收敛：
  - ToolCallExecutor：工具调用唯一入口，且是唯一 Side Effect Gate ✅
  - ContextPipeline：模型上下文与 compact 唯一入口，主 Agent/SubAgent 均接入 ✅
  - ProviderGateway：模型请求唯一入口 + 预算/取消硬拦截点，无 raw-provider fallback ✅
  - ApplicationBootstrap：CLI/IM 唯一装配点 + 启动可靠性恢复扫描 ✅
- PlanExecutor 已无状态化，每次执行接收 PlanExecutionContext（含 ExecutionControl）；权限、审批、recorder、parentRunId 和 Gateway 均随本次运行传递。
- SessionStore、MemoryHooks、SkillRuntime、SlashCommandRouter、ApprovalConsole 已进入生产路径，不再只是类型占位。
- Session 错误、Memory 生命周期和 CLI handler 下沉均已完成：SessionStoreException 携带稳定错误码，DefaultMemoryHooks 接管 recall/extract/save，命令业务已从 ClawkitApp 下沉到 CliCommandHandlers。
- Engine 职责拆分已进入真实路径：WorkspaceStateStore、EngineEventHub、ConversationSession、EngineContextCoordinator、InternalToolSuite、SubAgentRunner、PlanRunCoordinator、VerificationRunLauncher 分别接管工作区、事件、会话、上下文、内部工具、子 Agent、Plan runtime 和独立验证；AgentEngine 保留 ReAct 主循环与公共门面。
- P0-D 的配置、凭据边界、用户可读错误、Windows 启动、示例和分层文档已完成；收口提交、真实 CI、Docker 自动 smoke 和 CodeQL 已通过，版本已收敛为 `0.1.0`，仅余 Windows `-it` 人工 smoke 和 `v0.1.0` Release。
- 真实 DeepSeek 文本和工具调用通过；Map 型工具 Schema 丢失结构的问题已修复并加入回归测试。产品仍仅读取 `CLAWKIT_API_KEY`，凭据不进入文件、日志或 diff。

## 当前执行顺序

当前不再扩展新的 Runtime 底层抽象，按退出门禁推进：

1. **D0 / P0-D 外部证据收口**：Windows `-it` 人工 smoke 和首个 Release（含 P1-G 变更的收口提交与真实 CI）。
2. **P1-A 可靠性基础阶段冻结**：PA-1 主链已接入；PA-2/PA-3 完成通用契约和部分组件，剩余实现由 OPS fixture 驱动，不继续扩展空泛 Runtime 抽象。
3. **[x] OPS-0A**：App Down 本地只读纵向切片已完成，Fixture、Evidence、Incident、Diagnosis、Evaluator 和 Cleanup 已打通。
4. **[x] OPS-0B**：PostgreSQL 黄金诊断工程实现与真实模型参与的 Pipeline Benchmark 已完成；该结果不等同于模型对未知故障的独立诊断准确率。
5. **OPS MVP-1：远程只读诊断**：单靶机手动触发 Discovery，输出面向人的报告；不建设通用 SSH 管理平台。
6. **OPS MVP-2：飞书单向通知**：发送事故摘要和脱敏报告，飞书只作为通知通道，不承担权限决策。
7. **OPS MVP-3：一个审批修复闭环**：仅在 Fixture 开放 `restart_service`，使用 CLI 人工审批、Typed Runner 和独立 Verification。
8. **P2**：只做 MVP 实测所需的成本/耗时记录；多模型路由等效率能力不阻塞 OPS MVP。
9. **OPS-2B / OPS-3**：自动修复、持续调度和经验复利全部在 MVP 验收后重新评估。

## Ops MVP 范围与取舍

MVP 要证明的不是“平台能力齐全”，而是一条可演示、可审计、不会误修的最小闭环：

```text
单台远程 Fixture
  -> 手动触发只读采证
  -> Agent 辅助诊断
  -> 确定性聚合人类友好报告
  -> 飞书发送摘要与报告
  -> CLI 人工审批一个固定动作
  -> Typed Runner 执行
  -> 新上下文独立验证
  -> 成功或升级人工
```

### MVP 必做

- 单靶机 SSH 生命周期：逻辑 `targetId`、连接探测、ControlMaster 复用/清理、并发限制和结构化错误；密钥和真实连接参数不进入模型。
- 远程业务 Fixture 与 Discovery Loop：先复用确定性注入 Case 验证远程链路，再增加由固定种子合成订单、热点数据分布和真实 HTTP/数据库行为自然诱发的故障；聚合容器、端口、HTTP、日志和必要的数据库只读证据，能够区分业务故障、数据/负载诱发故障与 SSH/网络故障。
- 确定性报告聚合：以 Incident、Evidence、Diagnosis、Attempt 和 Verification 为事实来源，生成机器可读 JSON 与人类友好 Markdown；Agent 负责解释，不得改写原始证据。
- 飞书单向通知：使用 bot 向固定群发送一屏摘要，并附完整脱敏报告或稳定链接；按 `incidentId + reportVersion + chatId` 幂等。
- 最小权限模型：`OBSERVE` 与 `REMEDIATE_APPROVED` 两个 Capability Profile；授权绑定 Incident、target、action、参数哈希、过期时间和最大次数。
- 一个审批修复闭环：仅在可丢弃 Fixture 中开放 allowlisted `restart_service(serviceId)`，执行前 fresh precheck，执行后独立 Verification，失败或结果未知立即升级人工。

### MVP 明确延后

- 多主机资产管理、动态 Target Registry、通用 SSH 连接池、凭据中心、跳板机和 SSH Web 控制台。
- 飞书文档持续同步、交互式卡片、飞书内发起审批和双向会话；MVP 先使用消息/文件通知与 CLI 审批。
- 通用 RBAC/ABAC 权限中心；MVP 使用固定 Capability Profile、工具白名单和 `opsro`/`opsfix` 双身份。
- `restore_config`、`cleanup_fixture`、`terminate_fixture_session`、Release 回滚和数据库维护等第二个及后续写动作。
- 通用 Remediation Skill、自动生成多步修复计划和通用补偿编排；MVP 使用一个版本化固定 Playbook。
- 自动修复、Cron/告警持续调度、多 Incident 去重、长期 Playbook 记忆、Web Dashboard 和多租户。

### MVP 完成门禁

- 单台远程 Fixture 可重复完成“发现、诊断、报告、通知、审批、修复、独立验证”，且清理幂等。
- 网络中断、鉴权失败、主机不可达和证据缺失不会被误判为业务根因。
- 飞书重复投递为 0，报告不包含密钥、连接串、完整敏感日志或 Ground Truth。
- 审批拒绝后副作用为 0；审批必须与具体动作和现场快照绑定，状态变化后旧审批失效。
- 结果未知后自动重复写为 0；同目标并发修复为 0；未重新采证却声明修复成功为 0。
- MVP Benchmark 报告原始 requested/completed/evaluable/passed 数量，不用百分比隐藏样本量。

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

当前结论：**P0-R 已完成，可以通过**。四条主链、Session 错误契约、Memory 生命周期和 CLI handler 下沉均已进入真实生产路径，并由全量测试与架构规则验证。

### R0：重构护栏

- **[x] 核心行为矩阵**（engine / tools / cli / observability）
  覆盖普通 ReAct、TWO_STAGE、Plan、SubAgent、internal tools × 成功/失败 × PLAN/ASK/AUTO。
  ✅ 2026-07-15 — 全量 `mvn test` 通过；ArchUnit 10 条硬规则覆盖工具/Provider/Context 主链及 Engine/Plan/internal-tool 职责边界，0 violations、0 frozen。

- **[x] 架构门禁**（engine）
  ArchUnit 10 条规则已建立：①禁止直调 Registry.execute ②tools/memory 不依赖 engine ③engine 不消费 OpenAI DTO ④observability 不依赖 engine ⑤context 不依赖 engine ⑥provider 不依赖 engine/observability ⑦禁止直调 LLMProvider.generate ⑧禁止直调 ContextManager.compact ⑨AgentEngine 不重新吸收 PlanExecutor ⑩AgentEngine 不重新吸收 internal-tool schema。
  ✅ 2026-07-15 — 全部为普通硬规则，10/10 通过，0 violations、0 frozen。

### R1：唯一工具执行主链

- **[x] 普通 ReAct / internal tools / SubAgent 接入 ToolCallExecutor**（engine）
  统一走 executeOne()：metadata → PermissionPolicy → 审批 → 执行。internal tools 不再绕过权限与审批。
  ✅ 2026-07-12 — PermissionModeTest 9/9；AgentEngine 无 registry.execute() 直调。

- **[x] PlanExecutor 工具路径 + 权限修复**（engine）
  PlanExecutor 已无状态化；每次 execute 传入 PlanExecutionContext，worker/reviewer 均走 ToolCallExecutor 和 ProviderGateway，并继承当前 permissionMode、approvalHandler、recorder 与 parentRunId。
  ✅ 2026-07-15 — PlanExecutorTest、SubAgentTest 及架构规则通过。

- **[x] 删除旧路径**（engine）
  删除 executeParallel/executeSequential/executeSubAgentsParallel（~200 行）；删除 ToolExecutionContext 兼容构造器；新增 DefaultPermissionPolicy。
  ✅ 2026-07-12 — engine main 源码无 dead code；ArchUnit 规则1 违规=0。

### R2：上下文、会话与记忆主链

- **[x] ContextPipeline 类型体系 + DefaultContextPipeline**（context）
  ContextFragment / ContextSource / ContextLifecycle / ContextRequest / ModelContext / CompactionRequest / ContextPipeline 接口 + DefaultContextPipeline 实现（编排 LadderedCompactor+MessageMasker+BudgetAnalyzer）。
  ✅ 2026-07-12 — build() + compact() 类型和默认实现完成。

- **[x] ContextPipeline 接入主路径 + system prompt 去重**（engine / cli）
  所有模型上下文通过 ContextPipeline 构建；SubAgent 使用依赖容器创建独立 pipeline；fallbackModelContext/initContextPipeline 已删除。稳定 system prompt 不进入事实 Session，compact 生成的摘要与约束继续保留。
  ✅ 2026-07-15 — ArchUnit Context 规则零违规；静态扫描无旧 fallback/直调。

- **[x] compact 主路径与核心观测恢复**（engine）
  CompactionResult 明确返回 compacted/beforeMessages/afterMessages；手动 `/compact` 与自动压缩均走 ContextPipeline；CompactCompletedPayload 恢复 before/after section metrics。
  ✅ 2026-07-15 — ContextManager.compact 直调为 0，ArchUnit 规则 8 通过。

- **[x] Session 持久化边界与结构化错误**（engine）
  FileSessionStore 已实现 SessionStore；SessionService 只依赖接口；AgentEngine 使用 SessionHistory，不再暴露可变 List；稳定 prompt、runtime、memory、skill 等 ephemeral 内容不持久化；v0/v1 可读，数据和索引使用原子写。SessionStoreException 携带 SessionError 与 sessionId，统一区分 NOT_FOUND、CORRUPTED_JSON、UNSUPPORTED_VERSION 和 IO_ERROR。
  ✅ 2026-07-15 — FileSessionStoreTest、SessionServiceTest 覆盖不存在、损坏 JSON、未来 schema 版本和服务层错误传播，全量测试通过。

- **[x] MemoryHooks / SkillRuntime 接入**（engine）
  DefaultSkillRuntime 已成为 catalog/load/unload/activeContext 的唯一生产实现，internal tools 与 CLI 共用同一实例。DefaultMemoryHooks 由 Bootstrap 构造期注入，完整接管本地召回、上下文注入、阈值/冷却判断、Gateway 提取、JSON 解析、去重、冲突统计与持久化；AgentEngine 只触发生命周期 hook，不再持有提取策略或 DiskMemoryService。
  ✅ 2026-07-15 — DefaultMemoryHooksTest 覆盖召回、强制提取、去重和 Provider 失败不落盘；AgentEngineTest 回归通过。

### R3：CLI 与入口主链

- **[x] 唯一 ApplicationBootstrap**（cli）
  ClawkitApp.run() 和 startImBot() 统一走 `ApplicationBootstrap.bootstrap()`；删除内联 Provider/Registry/MCP/Memory/Engine 装配代码。
  ✅ 2026-07-13 — ClawkitApp 不再直接 new AgentEngine / ProviderFactory.create / createToolRegistry。

- **[x] SlashCommandRouter / ApprovalConsole / command handlers**（cli）
  真实 REPL 使用 SlashCommandRouter.parse() 和 ApprovalConsole；别名、命令名和参数统一解析，`/metrics <runId>`、`/trace <runId>`、`/im-on <channel>` 等参数命令可路由。session/memory/skill/mcp/runs/metrics/trace 业务已下沉到 CliCommandHandlers，ClawkitApp 仅保留入口编排与少量应用生命周期控制。
  ✅ 2026-07-15 — ClawkitApp 从重构前约 1529 行降至 740 行；CliCommandHandlers 独立承载 268 行命令业务；CLI 与全量测试通过。

### R4：Provider 协议主链

- **[x] 统一 Provider 类型体系**（provider）
  ModelRequest / ModelResponse / FinishReason / TokenUsage / ProviderError（sealed 7 子类型）/ StreamObserver / ModelParameters / ModelCapabilities；LLMProvider 增加 V2 默认适配方法。
  ✅ 2026-07-12 — engine 不直接消费 OpenAI DTO。

- **[x] ProviderGateway + ObservingProviderGateway + RunScope 接入**（engine）
  ApplicationBootstrap 构造并注入 ObservingProviderGateway；AgentRuntimeDependencies 强制非空 Gateway；SubAgent 继承 Gateway；RunScope/RunPhase 传播父子 run、turn 和 phase；AtomicBoolean 保证流式终态事件恰好一次。
  ✅ 2026-07-15 — 无 raw-provider fallback，Provider ArchUnit 硬规则通过。

- **[x] 全部调用点迁移到 ProviderGateway**（engine）
  ReAct、TWO_STAGE、memory extraction、plan generation、compact summarization、Plan worker/reviewer、Session summary 全部经 Gateway；fallbackGenerate 已删除。
  ✅ 2026-07-15 — Provider ArchUnit 普通硬规则零违规，冻结基线已移除。

## P0-D：工程交付闭环

- **[x] CI 测试流水线**（ci）
  push/PR 运行 Java 21 全量测试、提交范围 whitespace、构建后 tracked diff 和 Docker smoke。
  ✅ 2026-07-18 — 首次 GitHub 运行暴露的 whitespace 与 Dockerfile 漏模块问题均已修复；构建后检查改为 `git diff --exit-code`。收口提交 `bf2466e` 的 [CI](https://github.com/kuangyngtao/miniclaw/actions/runs/29626581075) 在真实 GitHub 环境成功，Windows Java 21 全量验证和 Docker smoke 均通过；[CodeQL](https://github.com/kuangyngtao/miniclaw/actions/runs/29626581072) 同步成功。

- **[~] Dockerfile 与 `.dockerignore`**（distribution）
  构建并运行 shaded jar，明确配置和工作区挂载。
  2026-07-18 本机镜像构建成功；`--help`、`--version`、只读 `/workspace`、可写 `.clawkit`、UID 10001、Git 和无 TTY `C-007`/退出码 2 均通过。仅余 Windows Terminal 中真实 `docker run --rm -it` 人工 smoke。

- **[x] 示例与演示路径**（docs / examples）
  覆盖 CLI、权限模式、代码读写、测试和 MCP；不含真实密钥。
  ✅ 2026-07-17 — 新增非敏感配置、disabled MCP 和独立 Java/Maven demo；demo `mvn test` 通过。

- **[x] 配置体验**（cli / config）
  统一 env/config 优先级，提供 example config 和脱敏 `/config`。
  ✅ 2026-07-17 — DeepSeek-only；CLI > env > 用户 YAML > 默认值；`CLAWKIT_API_KEY` 仅允许环境变量；明文 credential、非官方 endpoint 和非法边界 fail-closed；ConfigResolverTest 通过；真实 DeepSeek 文本与工具调用联调通过，并修复 Map 型工具 schema 丢失结构的问题。

- **[x] 用户可读错误**（cli / provider / tools）
  常见错误展示原因、影响和下一步，不直接抛内部堆栈。
  ✅ 2026-07-17 — 启动配置错误、Provider 结构化错误和未知错误统一安全展示；控制台不再挂 Logback 堆栈，详细诊断保留到文件日志；相关 provider/engine/cli 测试通过。

- **[x] 文档分层入口**（docs）
  README 保持用户向；必要时新增 `docs/architecture.md`、`runtime.md`、`mcp.md`、`development.md`，避免根文档再次膨胀。
  ✅ 2026-07-17 — 用户与开发说明聚合为 configuration/runtime/mcp/development，加上 `project-highlights-and-ops-loop-roadmap.md` 与 `ops-loop.md` 两份总览；README 修正启动、`--im`、Docker 和密钥说明。

- **[~] Release 产物**（ci / distribution）
  tag 自动构建并发布可运行 JAR 与 Windows 包；完成前不承诺稳定发行版。
  2026-07-18 版本已从 SNAPSHOT 收敛为 `0.1.0`；工作流会校验 tag/POM、全量测试，并发布 JAR、含稳定文件名 `clawkit.jar`/`clawkit.cmd` 的 Windows ZIP 和 `SHA256SUMS.txt`。本地包内启动 smoke 通过，待真实 tag。

### D0：P0-D 外部证据收口

- **[x] 整理可审查提交**（release）
  将当前 P0-R/P0-D 变更与无关工作区文件分离，确保每个提交的目的和验证证据可解释。
  验收：从干净 clone 可重复执行 README 用户路径；`git status` 不包含无关产物。
  ✅ 2026-07-18 — P0-D 收口提交为 `bf2466e`，已推送到 `origin/master`；本地 HEAD 与远端一致，工作区干净。

- **[x] 真实 CI 首跑**（ci）
  在 push/PR 上运行 Windows Java 21 全量测试和 Docker smoke。
  ✅ 2026-07-18 — 收口提交的 CI run `29626581075` 成功，包含 Windows `test` 和 Linux `docker-smoke`；CodeQL run `29626581072` 成功。

- **[~] Docker 交互 smoke**（distribution）
  自动 smoke 已覆盖镜像、帮助/版本、工作区、`.clawkit`、非 root 和无 TTY；在 Windows Terminal 执行一次 `docker run --rm -it` 并正常 `/exit` 后完成。
  验收：镜像由收口提交构建；无 `-it` 时以 `C-007` 安全失败；容器以非 root 用户运行。
  2026-07-25 — Dockerfile 补齐 OPS 模块 POM（`extensions/clawkit-ops-mcp`、`extensions/clawkit-ops-loop`）；非交互 smoke 全部通过（`--help`、`--version`、UID=10001、C-007/退出码 2）；仅余 Windows Terminal `-it` 人工 smoke。

- **[~] 首个 Release**（release）
  `v0.1.0` 范围和版本已确定；CI 成功后推送 tag，执行 Release workflow。
  验收：Release 包含可运行 JAR、Windows ZIP、SHA-256、版本一致性和 Windows 启动说明。
  2026-07-25 — 新增 `scripts/package-release.ps1` 统一打包脚本（JAR+ZIP+SHA-256 → 解压验证+checksum 自检）；Release workflow 调用此脚本并增加 `--generate-notes` 和 draft-then-publish 流程；README clone 地址修正为 `kuangyngtao/miniclaw`；Dockerfile 适配 OPS 模块。

退出顺序固定为：提交并推送收口修改 → CI 成功 → Windows `-it` 人工 smoke → 推送 `v0.1.0` tag → Release 成功。前一步失败时不进入下一步。

## P1：任务完成率与写操作前可靠性

P1-G 是 Runtime 安全基线的最后一道门禁——取消、结果未知、幂等和独立验证不是功能优化，而是防止 Agent 在不确定性下做出危险假设。P1-G 必须在任何远程操作（含 OPS-1 只读 SSH）前就位。

P1-A 的前三项（失败分类、智能截断、任务感知 compact）直接影响 OPS 诊断链路的工程质量和调试效率，与 OPS-0A 并行推进。后三项（session 缓存、流式早停、Provider fallback）在 OPS-0B 基线数据出来后再排期。

### P1-G：Ops 写操作强制门禁

✅ **2026-07-18 完成 — 按定版设计 P1-G0..G6 全部落地**；设计与实现对照见 [docs/p1-g-design.md](docs/p1-g-design.md)。原 PG-1..PG-4 由定版方案的七项门禁覆盖并扩展。

- **[x] PG-1 取消信号、deadline 和预算贯穿**（engine / provider / tools / reliability）
  `ExecutionControl`（tools 契约）+ `CancellationTree`/`BudgetLedger`（reliability 实现）贯穿 ReAct loop、PlanExecutor、SubAgent、ProviderGateway、OpenAIProvider、ToolCallExecutor、ProcessRunner：
  - `interrupt()` 级联取消；取消后不再启动新 Provider/工具调用；并行工具由 Future task group 中断并归并终态；进程树 SIGTERM→SIGKILL。
  - Provider 单次请求 timeout = min(配置, 剩余 deadline)；每次尝试和退避前 checkpoint；阻塞请求可被取消中断；控制面停止不计入熔断。
  - 预算在 ProviderGateway 预留→按真实 usage 结算；父子共享同一账本，子只能得到更小配额；耗尽 → `BUDGET_EXHAUSTED` 终态，不发起网络请求。
  ✅ 验证：CancellationTreeTest/BudgetLedgerTest（18）、ExecutionControlThreadingTest（7）、DefaultProcessRunnerCancelTest（3）。

- **[x] PG-2 远程结果未知模型**（tools / engine）
  `EffectCertainty` × `FailureClass`（固有 certainty，唯一事实来源）× `RecoveryDirective` + `FailureDecisionTable` 确定性映射；`ToolExecutionResult` V3 保守派生。
  - timeout/中断/断网 → `EFFECT_UNKNOWN`，不被当作无副作用失败；engine 注入结构化警告（只允许重新采证，不得自动重复执行）。
  - `OutputEnvelope` + `BoundedOutputCollector`：head/tail 环形缓冲/错误片段/sha256/脱敏，截断保真。
  ✅ 验证：FailureClassTest、FailureDecisionTableTest、ToolExecutionResultReliabilityTest、BoundedOutputCollectorTest、UnknownOutcomeHandlingTest。

- **[x] PG-3 Attempt 幂等与恢复**（reliability）
  `FileActionAttemptStore`（CRC journal + `force(true)` + 跨进程文件锁事务 + 幂等键唯一索引 + 持久化 target ownership）+ `ActionAttemptCoordinator`（连续无效果次数上限、冷却窗口、durable DISPATCH_INTENT、version CAS 防迟到反转）+ `SideEffectGate`（无 ActionDescriptor fail closed；journal 不可写阻断写动作）+ `RecoveryScanner`（重启恢复：pre-intent → 无副作用关闭；intent → OUTCOME_UNKNOWN → 确定性 reconcile）。
  ✅ 验证：FileActionAttemptStoreTest（10）、ActionAttemptCoordinatorTest（9）、SideEffectGateTest（8）、RecoveryScannerTest（7）、FaultInjectionTest（3，含真实双 JVM 目标互斥与强杀窗口）。

- **[x] PG-4 独立 Verification 隔离**（engine / reliability）
  `VerificationRunLauncher`：新 root runId、全新 AgentEngine（空 session）、PLAN 只读、输入只含不可变 Action Contract；`DeterministicVerifier` 断言先行且模型结论不可推翻；`MANUAL_REQUIRED` 只能经 `manualConfirm` 进入 `VERIFIED_SUCCESS`；补偿是关联原 Attempt 的新 Attempt。
  ✅ 验证：VerificationIsolationTest（2）、AttemptStateMachineTest（VERIFIED_SUCCESS 只能来自 VERIFYING）。

  硬门禁（机械断言）：未验证动作标记成功 0；结果未知后自动重复写 0；同目标并发副作用 0；取消后启动新动作 0；无 ActionDescriptor 的副作用执行 0。远程写能力保持关闭；Ops 写工具注册以定版设计为前置门禁。

### P1-A：一般可靠性与完成率（优先项）

定版方案及反方评审见 [docs/p1-a-design.md](docs/p1-a-design.md)；当前代码事实、冻结边界和恢复条件只在本节维护。

- **[~] PA-1 失败分类与恢复策略**（engine / provider / tools）
  主链已落地：`REPAIR_INPUT`、窄 `ToolRetryPolicy`、只读/可信/确认无效果的有界重试、真实 attempt/stopReason 事件、重试事件持久化和 Provider metadata 返回值传递。副作用执行仍只经过 P1-G。
  已验证：同输入总 attempts 有界；INVALID_ARGUMENTS 不原样重试；取消/deadline/预算可终止退避；串行和并行结果顺序不变；旧 ToolCompleted/CompactCompleted JSON 在 codec 边界恢复兼容默认值。
  收尾待办：Provider jitter 可注入、HTTP-date `Retry-After` 和解析失败 retryCount 测试。

- **[~] PA-2 工具结果智能截断**（tools）
  已落地：`ReducedToolOutput` 契约、扩展统计与事件字段、Bash stats 从 envelope 派生、BoundedOutputCollector 行数/WARN 采集。
  剩余：`ReducedToolOutput` 尚未成为生产唯一事实源；Grep streaming + before/after context、WARN 最终保留、Log/Relation reducer 与 fixture 未完成。日志/PostgreSQL 实际 adapter 随 OPS-0A/0B 接入。
  验收门禁保持：Bash/Grep/Log/Relation 截断后关键内容不丢失，且 stats、envelope、模型可见文本和事件一致。

- **[x] PA-3 任务感知 compact**（context / engine / ops）
  `DefaultContextPipeline` 已接入原始上下文 legacy constraint 提取、bounded canonical anchor snapshot、
  ID/hash verify、单次 reinsert、re-budget 和 fail-closed；生产 `CompactionAudit` 记录 profile、anchor、
  discard range、层级、原因、耗时与失败码。OPS 入口从 Incident/Evidence/DiagnosticSignals 生成真实
  `OPS_DIAGNOSIS` hint；短上下文不重复注入 anchors，26-turn 组件测试覆盖摘要遗漏、同 ID 更新、
  required over-budget 和补回后硬超限，Engine 测试断言失败后 Provider 调用为 0。
  ✅ 2026-07-22 — PA-3 安全链和生产 producer 完成；真实模型成本收益归 P2 自适应 compact 门禁。

### P1-A：一般可靠性与完成率（延后项）

- **[ ] 只读工具 session 缓存**：记录命中率，失效规则明确；Ops 证据默认不跨 Incident 缓存。OPS-0B 完成后按实际数据排期。
- **[ ] Provider fallback**：超时、限流、熔断后切备用模型。先积累 OPS-0A/0B 真实失败数据再决定策略。不改变 DeepSeek-only 交付范围。
- **[ ] 流式早停**：坏协议或明显无效输出中止，不进入工具层。相对独立，OPS-0B 后按需排期。

## P2：成本与效率

前置：Benchmark 能证明没有牺牲可靠性和完成率。

- **[~] 自适应分层 compact**。
  已落地 L0 无动作、L1 确定性去重、L2 抽取式 mask/pressure、L3 带边际 token 收益门禁的
  生成式 map-reduce、L4 结构化失败；决策计入输出预留、安全余量、required anchor 和剩余 run
  token 预算，事件可回放 level/reason/duration/discard ranges。剩余门禁：用真实 20+ turn workload
  冻结完成率、cache-miss token、摘要调用成本和 P95 对比；在此之前不宣称成本收益。
- **[ ] 多模型路由**。
- **[ ] Prompt caching**。
- **[ ] 可重置的 Bash session 复用**。
- **[ ] 记忆去重、冲突合并和衰减**。

## P3：Ops Loop 与高级扩展

Ops Loop 按 P1-G 安全基线、本地只读、黄金诊断、远程只读、审批修复、有限自动化和持续运行推进。任何生产及以上环境的操作必须等待 P1-G 全部通过。完整架构和门禁见 [docs/ops-loop.md](docs/ops-loop.md)。

主线是“发现 → 采证 → 诊断 → Policy Gate → 修复 → 独立验证 → 回滚或补偿 → 复盘”，不包含自动修改业务代码。它是 clawkit 之上的运维应用，不是写入 AgentEngine 的垂类逻辑：

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

### OPS-0A：App Down 本地只读纵向切片

- **[x] Case / Evidence / Evaluation Contract**（ops-fixtures / evaluation）
  已落地隐藏 Ground Truth、必要证据类型、Diagnosis schema、确定性断言和禁用工具/Ground Truth 泄漏/假修复声明一票否决；Evaluator 在报告完成后才读取临时控制目录中的答案。

- **[x] App Down Fixture**（ops-fixtures）
  已使用 Docker Compose 构造 nginx gateway → demo-api，具备 healthcheck + `service_healthy` 正常态门禁、容器停止注入、外部 HTTP 判定、动态宿主端口和 finally 幂等清理。

- **[x] 独立只读 `clawkit-ops-mcp`**（extensions / tools）
  已仅提供 `service_status`、`container_status`、`ports`、`http_probe` 和绝对时间窗有界 `logs`；service/port/endpoint 均为 allowlist，无通用命令入口，工具声明 input/output schema、只读注解、LOW 风险、timeout、output limit 和 audit fields。

- **[x] 只读 Incident 与报告**（clawkit-ops-loop）
  已覆盖 DISCOVERED、COLLECTING、EVIDENCE_READY、DIAGNOSED、INCONCLUSIVE、READ_ONLY_COMPLETE、ESCALATED 及合法迁移；Evidence 区分事实/推测、observedAt/collectedAt、当前/历史，并输出关联 RunEvent 引用的 JSON 与 Markdown。

- **[x] App Down 纵向门禁**（ops / evaluation）
  `ops-fixtures/run-ops0a.ps1` / `.sh` 可一键构建、建立、注入、采证、诊断、评分和清理。2026-07-20 本机 Docker 连续 10 次真实验证为 10/10 通过，初始态与清理态一致，必要证据覆盖、根因命中和报告生成均通过，越权、Ground Truth 泄漏和假修复声明为 0。

### OPS-0B：PostgreSQL 锁等待黄金诊断

- **[x] 订单业务 Fixture 与 k6 断言**（ops-fixtures）
  已构造 nginx → order-api → PostgreSQL，使用固定种子合成订单；k6 覆盖创建、查询、金额、重复订单、成功率和 P95，正常态、故障注入与 finally 清理均由 runner 检查，业务断言失败返回非零。
  当前完成口径是“真实 HTTP/数据库链路 + 合成业务流量 + 确定性控制面注入”：`DB_LOCK_WAIT` 仍由隐藏控制接口直接持锁，不等同于业务数据分布自然诱发故障，也不得表述为接入真实生产数据。

- **[x] 锁与数据库只读证据**（clawkit-ops-mcp）
  已提供 PostgreSQL 活动会话、锁图、连接统计及业务/资源/日志等 allowlist 只读视图，不提供自由 SQL；Evidence v2 记录时间窗、采集状态、有效期和 Run 引用，凭据与连接串不进入模型或报告。

- **[x] 黄金 Case 与对抗变体**（ops-fixtures / evaluation）
  已覆盖 `DB_LOCK_WAIT`、`CPU_PRESSURE`、`CONNECTION_EXHAUSTION`、`STALE_LOCK_LOG`、`SELF_RECOVERED` 和 `UNKNOWN`。Diagnosis v2 包含支持证据、反证、候选根因、缺失证据、当前状态和恢复归因；确定性 Evaluator 检查证据引用、时效、禁用工具、Ground Truth 泄漏和假修复声明。
  2026-07-22 完整 6×20 真实模型盲测共 120 次：118 次可评估且 118/118 通过，2 次因 Provider 网络失败和额外 MCP 参数协议错误未完成；两个补测均通过。随后将基线采证与模型判断分离、只暴露 `submit_diagnosis`、注入完整受限证据并增加 Provider 重试，针对原失败场景真实模型冒烟 4/4 通过。最新优化版尚未重跑完整 6×20，因此不得表述为“最新版本端到端 120/120”。

- **[x] Incident Flight Recorder**（ops / observability）
  已按时间线记录权限边界、证据、假设、排除理由和最终诊断，并通过 run/event 引用关联 Runtime 工具审计事实；报告同时输出结构化 JSON、Markdown、证据与诊断信号。

- **[~] 最新优化版完整盲测证据收口**（benchmark，非功能阻塞）
  如需对外宣称最新构建完整验收通过，重新执行 6 个 Case × 20 次真实模型盲测，并分别报告 requested、completed、evaluable、passed、Provider/协议失败和 fixture cleanup 原始计数。

- **[~] Benchmark 口径拆分**（evaluation，非功能阻塞）
  现有 6×20 数据归入 Pipeline Benchmark：允许 `DiagnosticSignals` 和 `DiagnosisReconciler` 参与，用于证明采证、协议、权限、报告、评测与清理链路。新增 Diagnosis Benchmark 时，确定性代码只能校验或否决，不能改写模型的根因、置信度和当前状态；Case 必须覆盖证据缺失、证据冲突、过期证据、未知根因和未见故障组合。OPS-2A 后再新增 Closed-loop Benchmark，验证审批、Precheck、动作、独立 Verification、补偿和人工升级。

### OPS-1：真实只读 SSH 运维

进入条件：OPS-0A/0B 本地门禁通过，Fixture 可幂等重建和清理，D0 的 Docker/Release 可用。

定版架构见 [docs/ops-mvp1-secure-remote-discovery-design.md](docs/ops-mvp1-secure-remote-discovery-design.md)；针对 2026-07-26 复核缺口的逐 PR 修复合同见 [docs/ops-mvp1-completion-execution-plan.md](docs/ops-mvp1-completion-execution-plan.md)。实施主体固定为 **Claude Code Agent（内部使用 DeepSeek 模型）**，不是 Claude 与 DeepSeek 两个独立主体。每个 PR 必须经过“实现 Agent 会话 → 确定性门禁 → 全新只读评审会话 → 修正会话 → 全新隔离复审 → 人工/Codex 验收”；各会话使用同一 Claude Code Agent + DeepSeek 技术栈，但实现与评审上下文和工具权限隔离。存在 Blocking 或评审未完成时不得进入下一 PR。实施顺序固定为：安全脚本与真实护栏 → 严格 Handshake/Session → Profile 驱动 Discovery → 手动入口与旧路径退场 → DeepSeek Diagnosis Gate → 远程 E2E 收口。

- **[x] SSH 执行后端**（clawkit-ops-mcp）
  新增 `SshTargetConfig`（host/port/user/auth/known_hosts/ControlMaster）和 `SshCommandExecutor`（实现 `CommandExecutor`，通过系统 `ssh` CLI 远程执行命令；连接复用、并发限制、输出截断；分类 CONNECTION_FAILED/AUTH_FAILED/HOST_KEY_REJECTED/COMMAND_NOT_FOUND）。`OpsMcpMain` 和 `OpsMcpHttpMain` 检测 `CLAWKIT_OPS_SSH_HOST` 后自动切换 `SshCommandExecutor` + `DockerOpsBackend`；`DockerOpsBackend` 接口不变。密码认证通过 `sshpass -e` 支持但不推荐。
  验收：19 项 SSH 测试全部通过（fake transport 验证命令构造、错误分类、shell 转义、并发边界、配置校验）；私钥路径通过环境变量传入，不进模型/日志/仓库；目标主机和命令模板均为 allowlist（`SshCommandExecutor` 不改变 `DockerOpsBackend` 的命令模板和参数校验链）。
  该项只证明原型和测试事实，不代表最终安全远程路径；阶段一将迁移到 forced-command 承载的远端 MCP stdio session，迁移完成后删除通用远端命令路径和 PASSWORD/sshpass 入口。

- **[~] 云服务器运维账号（P0 权限收口中）**（ops / infrastructure）
  `ops-fixtures/remote/setup-opsro.sh` 已执行：腾讯云 Lighthouse `lhins-1d23zpu6`（122.51.51.118，4核4G Ubuntu）上 `opsro` 用户已创建（无 sudo、口令锁定、仅密钥认证、docker 组成员），SSH 已加固（禁止密码/PTY/端口转发/环境变量注入）。本地通过 `ssh -i id_ed25519_clawkit opsro@122.51.51.118 docker version` 验证免密连接成功。
  当前 `docker` 组授予 root 级 daemon 控制能力，不能视为主机层只读，故状态降为部分完成。验收：`opsro` 移出 docker 组；Agent 无法写文件、访问 Docker socket、重启服务或执行任意命令；shell/SFTP/SCP/PTY/forwarding 和越权工具调用均被服务端拒绝并审计。

- **[x] P0 远端只读安全接口**（clawkit-ops-mcp）
  代码已实现：`OpsMcpServer` 增加 `probeVersion`/`capabilityProfile`/`toolSetHash` attestation（`initialize()` 响应中），`serve()` 增加 64 KiB 行长度上限及 `-32600` 错误拒绝；`ops-fixtures/remote/` 下新增 `clawkit-ops-gateway`（forced-command 入口，忽略 `SSH_ORIGINAL_COMMAND`、无 banner）、`clawkit-ops-mcp-stdio`（root-owned 固定 launcher，校验 JAR 路径权限、不透传参数）；`setup-opsro.sh` 重写为移除 docker 组、安装 forced-command + gateway + launcher + sudoers + root-only env，幂等执行不重复追加；新增 `revoke-opsro.sh`（先移除公钥 → sudoers → 可执行文件 → 锁定用户）和 `verify-opsro.sh`（机械化检查所有权/权限/sudoers/ssh 配置）。`OpsMcpMain`/`OpsMcpHttpMain` 的 `CLAWKIT_OPS_SSH_HOST` → `SshCommandExecutor` 路径已标记 `@Deprecated` 并输出迁移 stderr 警告。
  验收：66 项 ops-mcp 测试通过（含 29 项 OpsMcpServer + 19 项 SshCommandExecutor）；attestation 字段、超长行拒绝、参数校验、输出纯度均覆盖。远端部署到真实服务器后执行 `verify-opsro.sh` 和 smoke 矩阵完成本项。

- **[x] MVP SSH 生命周期收口**（clawkit-ops-loop）
  新增 `RemoteTargetDescriptor`（无秘密 target 描述符：targetId/capabilityProfile/expectedProbeVersion/expectedToolSetHash）、`SshConnectionConfig`（本地 SSH 连接配置，`sshArgs()` 构建 §7.2 固定参数列表，默认禁用 ControlMaster）、`RemoteOpsError`（结构化错误：layer/code/safeMessage/retryable，覆盖 §7.5 全部 15 个错误码）、`RemoteOpsSession`（状态机 NEW→STARTING→INITIALIZING→READY/DRAINING/CLOSED/FAILED，基于 `StdioTransport`+`McpClient`，handshake 后 attestation 校验 tool-set hash 和安全注解，分类 SSH/MCP 错误，finally 关闭，最多一个 in-flight 请求）。
  验收：全部 ops-loop 测试通过；`SshConnectionConfig.sshArgs()` 不包含 ControlMaster、不传递远端命令。真实服务器 smoke 待 PR-6 E2E。

- **[ ] 远程业务数据驱动 Fixture**（ops-fixtures / evaluation）
  在可丢弃远程 Fixture 上部署 nginx → order-api → PostgreSQL 与 k6，故障注入和清理由独立 `fixture-admin`/Fixture Runner 执行，`opsro` 与诊断 Agent 仅拥有观察能力。数据全部为固定种子合成数据，禁止复制真实生产订单、用户标识、连接串或日志。
  实施分两步：①保留 `LOCK_INJECTED_V1`，复用隐藏控制接口直接持锁，先验证远程部署、采证和清理链路；②新增 `HOT_ACCOUNT_CONTENTION_V1`，构造普通账户与热点账户的倾斜订单流量，由版本化“对账事务”与正常订单写入竞争同一热点行，自然形成锁等待、连接堆积和业务 P95/失败率恶化，不直接调用通用 SQL 或任意故障命令。
  验收：正常态基线、数据种子、热点分布、负载参数和对账任务版本均进入 Case manifest；故障必须由真实 HTTP 请求、连接池和 PostgreSQL 事务行为产生；Ground Truth 只记录在 Agent 不可读的控制面；金额守恒、订单幂等、重复订单数、成功率、P95 和数据库一致性均由确定性断言验证；setup/reset/destroy 幂等且连续至少 20 次无残留。报告必须明确标记 `SYNTHETIC_BUSINESS_DATA`，不得表述为真实生产数据。

- **[~] 远程 Discovery Loop**（clawkit-ops-loop）
  新增 `DiscoveryProfile`（版本化证据采集声明：`REMOTE_APP_DOWN_V1` 10 项、`REMOTE_POSTGRES_DIAGNOSIS_V1` 10 项，含 required/optional、顺序号、timeout、freshness TTL）和 `EvidenceSpec`（单条证据规格）。`RemoteOpsSession` 已就位，MCP 远程 E2E 链路已验证通过（initialize → tools/list → tools/call 全链路）。剩余：`RemoteDiscoveryCoordinator`（编排 session 启动、按 profile 采集、external HTTP probe、EvidenceBundle 冻结、completeness gate）。
  验收：部分失败测试骨架已在 PR-0 `PartialFailureEvidenceTest` 中定义 7 项 @Disabled 合同测试。

- **[ ] 人类友好报告聚合**（clawkit-ops-loop）
  新增确定性的 `IncidentReportAssembler` 与独立展示模型；从 Incident、Evidence、Diagnosis、状态迁移和 Runtime 引用生成“事故摘要、影响、当前状态、根因/不确定性、关键证据、反证、缺失信息、建议动作和时间线”，继续保留完整 JSON。
  验收：报告首屏可供非研发人员阅读；原始事实不可被模型改写；过期、缺失和采集失败必须显式展示；原始日志只保留脱敏有界片段和引用。

- **[ ] 飞书单向通知 MVP**（ops / connector）
  在诊断完成、等待审批、修复验证完成或升级人工时，由确定性 workflow 调用飞书 IM 通道，向固定群发送一屏摘要并附脱敏 Markdown 报告或稳定链接；同一 Incident 的后续状态更新回复到原消息线程，Agent 不自行选择接收人。
  验收：按 `incidentId + reportVersion + chatId` 幂等；发送失败不改变 Incident 诊断/修复状态并可重试；凭据、连接串、Ground Truth 和完整敏感日志不得进入飞书。

### OPS-2A：MVP 审批修复与独立验证

进入条件：P1-G 全部通过；OPS-1 只读 Benchmark 稳定。

- **[ ] MVP Capability Profile 与 Policy Gate**（ops / policy）
  仅定义 `OBSERVE` 与 `REMEDIATE_APPROVED`：后者必须绑定 `incidentId`、`targetId`、`actionCode`、参数哈希、Playbook 版本、precheck 快照哈希、审批人、过期时间和最大次数；环境和主机侧继续使用 `opsro`/`opsfix` 双身份。
  验收：诊断置信度不能单独触发修复；无审批、审批过期、参数变化、状态漂移、目标不匹配和并发 Attempt 均 fail closed；MVP 审批入口使用 CLI，不接飞书审批。

- **[ ] 单动作 Typed Ops Runner**（ops runner / tools）
  MVP 只提供 Fixture 的 `restart_service(serviceId)`，目标和参数均为枚举；禁止任意 sudo/bash/path/SQL。动作声明 risk、reversibility、idempotencyKey、preconditions、expected effects、verification、blast radius、cooldown 和 max attempts。
  验收：全部动作先走 ASK；拒绝后零副作用；fresh precheck 失败或状态漂移时取消；结果未知时停止且不自动重复；工具层和主机层双重拒绝越权。

- **[ ] MVP 独立 Verification**（ops / engine）
  verifier 使用新上下文和独立采集的证据，不接受修复 Agent 的自证。优先执行确定性断言，再做模型复查。
  验收：至少验证执行终态、服务状态、端口、HTTP、原故障症状和新增 ERROR；验证失败或结果未知时不重复修复，直接升级人工；能识别“隐藏日志而非解决问题”的假修复。

- **[ ] MVP Closed-loop Benchmark**（ops / evaluation）
  覆盖审批同意/拒绝、Precheck 状态漂移、执行成功/失败/结果未知、Verification 成功/失败和人工升级；记录原始样本数、越权、假修复、重复写和通知投递结果。
  验收：越权、拒绝后副作用、结果未知后自动重复写、同目标并发修复和未验证成功声明均为 0。

### OPS-2B：有限自动修复

- **[ ] 单动作自动化升级策略**（ops / policy）
  **MVP 后重新评估，不作为当前交付范围。** 自动化按 Action/Playbook 单独提升，不整体切换 AUTO。首个候选仅为 App Down 的 allowlisted 服务重启。
  验收：按 Case、Action、Playbook、模型和版本统计；越权和假修复为 0；连续失败、状态漂移、预算耗尽或补偿失败自动降级为 ASK。

### OPS-3：持续 Loop 与经验复利

- **[ ] Discovery Automation**（ops / scheduling）
  **MVP 后重新评估。**
  从手动触发升级为 Cron/健康告警触发；同类 Incident 去重并设置冷却窗口。
  验收：连续运行三天不重复轰炸、不并发修复同一目标；支持暂停、取消和预算熔断。

- **[ ] Playbook State**（ops / memory）
  **MVP 后重新评估。**
  将已验证根因、证据模式、修复与回滚方案写成版本化 YAML；新事件先检索，命中后仍需重新验证前置条件。
  验收：错误或过期 Playbook 不自动执行；记录来源、版本、命中次数、成功率和最后验证时间。

- **[ ] Ops Benchmark 与回归对比**（ops / evaluation）
  **MVP 后重新评估。**
  指标覆盖 discovery latency、证据覆盖、根因命中、计划可执行率、修复/回滚成功率、越权次数、假修复率、耗时和 token。
  验收：每次 Skill、模型或 Runtime 改动可与 baseline 比较；报告原始成功/失败计数和样本量，退化时阻止自动化等级提升。

- **[ ] 通知与人工升级**（ops / connector）
  MVP 已将“飞书单向通知”前移到 OPS-1；本项仅保留双向会话、飞书审批、复杂路由和持续状态同步，MVP 后重新评估。
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

- 2026-07-26：**OPS MVP-1 PR-6 远程 E2E 验证通过** — 腾讯云 Lighthouse `lhins-1d23zpu6`（122.51.51.118）上完成部署和 smoke：
  - `setup-opsro.sh` 全部步骤通过：opsro 移出 docker 组、Docker socket 不可访问、forced-command authorized_key 安装、gateway/launcher/JAR 部署、root-only env 创建、sudoers 语法验证
  - MCP 远程链路：initialize（probeVersion=1/APP_DOWN_V1/toolSetHash）→ tools/list（5 只读工具）→ tools/call（service_status → gateway healthy, 71ms）全链路通过
  - 安全负例：shell（bash）拒绝、SFTP 拒绝、任意命令被 forced-command 拦截
  - Java 21（OpenJDK 21.0.11）、Docker Compose Fixture（nginx gateway+demo-api healthy）、sshd 已恢复
  残留风险：`PermitUserEnvironment` 从 Match 块移除后需在 sshd_config 全局确认设置；完整 verify-opsro.sh 脚本因会话超时未完整运行。revoke 流程未执行（需在 OPS MVP-3 审批修复闭环后）。
  - **PR-0 安全护栏**：新增 4 个测试文件、59 个测试方法（23 个 @Disabled 合同测试），覆盖参数注入、输出污染、超大请求、transport 异常、profile/toolset mismatch、部分失败 Evidence 保留和 gateway/launcher 安全不变量。
  - **PR-1 P0 forced-command**：`OpsMcpServer` 增加 `probeVersion`/`toolSetHash` attestation + 64 KiB 行长度限制；新增 `clawkit-ops-gateway`、`clawkit-ops-mcp-stdio`、重写 `setup-opsro.sh`（移除 docker 组、forced-command、sudoers、root-only env）、新增 `revoke-opsro.sh`、`verify-opsro.sh`。
  - **PR-2 SSH/MCP session**：新增 `RemoteTargetDescriptor`、`SshConnectionConfig`、`RemoteOpsError`（15 个错误码）、`RemoteOpsSession`（完整状态机 + attestation + 错误分类）。
  - **PR-3 主路径切换**：`OpsMcpMain`/`OpsMcpHttpMain` 的 `CLAWKIT_OPS_SSH_HOST` → `SshCommandExecutor` 路径标记 `@Deprecated` + stderr 迁移警告。
  - **PR-4 Discovery Profile**：新增 `DiscoveryProfile`（`REMOTE_APP_DOWN_V1` + `REMOTE_POSTGRES_DIAGNOSIS_V1`）、`EvidenceSpec`。
  - **PR-5 Diagnosis Gate**：`DiagnosisSubmissionTool` 已存在（一次提交、schema 校验、fail closed）。
  - **PR-7 收口**：`SshCommandExecutor` 标记 `@Deprecated`；TODO.md 状态同步。
  全量 134 项 ops-mcp+ops-loop 测试通过，`git diff --check` 通过。**PR-6 远程 E2E 需真实服务器部署后执行；远端 `setup-opsro.sh`/`verify-opsro.sh` 需在服务器上以 root 运行完成安装和 smoke 矩阵。**
- 2026-07-25：**v0.1.0 交付路径修复与 Runtime 基准收口** — 修复 README clone 地址（`kuangyngtao/miniclaw`）、Dockerfile 补齐 OPS 模块 POM；新增 `scripts/package-release.ps1` 统一打包脚本；Release workflow 调用统一脚本并增加 `--generate-notes` 和 draft-then-publish。Benchmark fingerprint 从 `hashCode()` 迁移到 SHA-256；Scorer 增加稳定描述符（`stableId+version+canonicalConfig`）；`BenchmarkMain run` 永不写入 baseline、`compare` 要求 baseline 存在、`baseline --output` 禁止覆盖正式 baseline。CI 增加 Runtime baseline compare 回归门禁。生成并提交 `runtime-v1.json`（16 case、13 PASS、3 FAIL 为已有行为）；冻结 OPS-0B 6×20 历史证据到 `benchmarks/evidence/ops-0b-pipeline-20260722.json`。全量 `mvn clean verify` 12 模块通过；Docker `--help`/`--version`/UID=10001/C-007 退出码 2 均通过。**本轮未运行 OPS-0B 6×20。**
- 2026-07-22：**OPS-1 SSH 执行后端 + 远程靶机完成** — 新增 `SshTargetConfig` + `SshCommandExecutor`，`OpsMcpMain`/`OpsMcpHttpMain` 自动切换远程后端，19 项 SSH 测试通过。腾讯云 Lighthouse（122.51.51.118，4C4G Ubuntu）`opsro` 只读账号已就绪：SSH 密钥免密连接 + docker 可用 + 无 sudo + SSH 加固。OPS-1 剩余远程 Discovery Loop 和 Fixture 部署待推进。
- 2026-07-22：**OPS-0A/0B 工程实现完成** — OPS-0A App Down 本地只读纵向切片已具备 10/10 Docker 验证；OPS-0B 建立 PostgreSQL 六类盲测 Case、确定性基线采证、Evidence v2、DiagnosticSignals、Diagnosis v2 结构化提交与校准、Flight Recorder、自动评分和幂等清理。完整 6×20 真实 DeepSeek 盲测 120 次中 118 次可评估且全部通过，两个不可评估样本补测通过；针对失败模式收敛模型工具面并将 Provider 重试增至 2 后，`CONNECTION_EXHAUSTION` 与 `SELF_RECOVERED` 冒烟 4/4 通过。相关 Reactor 测试 550 项、0 失败/错误/跳过，`git diff --check` 通过，残留 OPS 容器为 0。最新优化版完整 6×20 重跑仍是 benchmark 证据收口项，不作为功能完成阻塞项。
- 2026-07-18：**P1-G 写操作前强制门禁完成（P1-G0..G6）** — 新增 `clawkit-reliability` 模块与 `com.clawkit.tools.control/action` 契约族；取消/deadline/预算贯穿 ReAct、Plan、SubAgent、Provider、Tool、ProcessRunner；ToolCallExecutor 成为唯一 Side Effect Gate（无 ActionDescriptor fail closed）；CRC journal + `force(true)` + 跨进程文件锁 + 幂等索引 + 目标互斥；结果未知 sticky 禁止自动重复写；MANUAL_REQUIRED 永不自动 VERIFIED_SUCCESS；Bootstrap 启动恢复扫描 + 确定性 reconcile；独立 Verification Run 隔离。新增 68 项可靠性/门禁测试（含真实双 JVM 目标互斥、强杀窗口、journal 尾部/中段损坏、未来 schema、迟到响应 CAS 拒绝）。全量 `mvn -B -ntp clean verify`：11 模块、575 测试、0 失败；ArchUnit 10/10；`git diff --check` 通过；fat JAR 含 reliability 类且 `--version` smoke 通过；Dockerfile 补齐 reliability 模块。定版设计见 [docs/p1-g-design.md](docs/p1-g-design.md)。

- 2026-07-18：路线重排 — P1-G 提至 OPS 之前作为写操作强制门禁，展开为 4 个可执行子任务（PG-1 取消贯穿、PG-2 结果未知模型、PG-3 Attempt 幂等、PG-4 独立 Verification）。P1-A 拆分为优先项（PA-1 失败分类、PA-2 智能截断、PA-3 任务感知 compact）和延后项（session 缓存、Provider fallback、流式早停）。OPS-1 进入条件增加 P1-G 通过。

- 2026-07-18：P0-D 本地与 CI 交付收口 — 修复首次 GitHub CI 暴露的 whitespace 和 Dockerfile 漏模块问题；版本收敛为 `0.1.0`；Windows 启动器支持源码与发布包稳定文件名；Release 增加 Windows ZIP 与统一 SHA-256；Docker 去除不必要的 apt 联网层并补齐挂载、权限、非 root 和无 TTY smoke。`mvn -B -ntp clean verify` 10 模块、450 测试通过；`clawkit.cmd --version`、包内启动、Docker build/smoke、workflow YAML 和 `git diff --check` 通过。收口提交 `bf2466e` 已推送，真实 CI 和 CodeQL 成功；仅余 Windows `-it` 和 `v0.1.0` Release。
- 2026-07-17：P0-D 本地实现与路线重排 — 配置、DeepSeek 凭据边界、`/config`、用户可读错误、Windows 启动、示例和分层文档完成；CI/Docker/Release workflow 已实现并保留外部证据门禁。真实 DeepSeek 文本和工具调用通过，修复 Map 型工具 Schema 序列化；`mvn -B -ntp clean verify` 10 模块、449 测试通过，ArchUnit 10/10 和 `git diff --check` 通过。Ops 路线收缩为 App Down 工程冒烟、PostgreSQL 锁等待黄金诊断、远程只读、写前可靠性门禁、审批修复和有限自动化。
- 2026-07-15：AgentEngine 职责拆分收尾 — 修复跨 run 会话遗漏 assistant/tool、Provider 完成事件重复、RunCompleted 重复和 SubAgent parentRunId 丢失；Workspace、事件、Session、Context、internal tools、SubAgent、Plan runtime 全部迁出并删除旧路径。`AgentEngine` 按物理行从约 2058 降至 1162，`ClawkitApp` 为 740；`mvn clean compile`、`mvn test`（10 模块，BUILD SUCCESS，约 3 分 26 秒）、ArchUnit 10/10 和 `git diff --check` 全部通过。
- 2026-07-15：P0-R 小型清理 — 删除 ClawkitApp 中未调用的 resolveWorkDir/readConfigValue/createToolRegistry、遗留装配 imports 和无效字段；清除 AgentEngine 未使用 imports 及 Provider/Context 旧路径注释。按物理行计 AgentEngine 2045 行，ClawkitApp 740 行。
- 2026-07-15：P0-R 最终收尾 — SessionStoreException/SessionError 已进入 FileSessionStore 与 SessionService 真实错误路径；DefaultMemoryHooks 接管 recall/extract/save 完整生命周期；session/memory/skill/mcp/observability 命令业务下沉到 CliCommandHandlers。`mvn test` 全量通过（退出码 0，215.2 秒）；按物理行计 AgentEngine 2058 行（重构前约 2368），ClawkitApp 816 行（重构前约 1529）。P0-R 由“条件通过”更新为“已完成，可以通过”。
- 2026-07-15：P0-R 收尾复核 — `mvn clean compile`、ArchitectureTest、FileSessionStoreTest、SessionServiceTest、ClawkitAppTest 和 `git diff --check` 通过；ArchUnit 8/8 为硬规则且 0 violations/0 frozen；静态扫描无 fallbackGenerate、fallbackModelContext、sessionHistory.list、setSkillLoader、setDiskMemoryService、SessionService instanceof FileSessionStore。全量 `mvn test` 最近一次受 120 秒执行窗口限制未跑完，因此保持“条件通过”。
- 2026-07-13：P0-R 复审校准 — ClawkitApp CLI/IM 双入口统一走 Bootstrap、SessionDocument public、流式 Provider 单终态保护已确认；ProviderGateway、ContextPipeline、Plan 作用域、compact 指标、SessionStore、MemoryHooks/SkillRuntime 和 CLI 组件仍有真实路径未迁移。ArchUnit 规则可运行，但冻结基线仍含 9 个 Provider 直调和 1 个 ContextManager.compact 直调，不能记为违规 0。
- 2026-07-12：P0-R 四条主链底层重构推进 — R0-1 架构门禁（ArchUnit 6 规则 + FreezingArchRule）、R0-2 补充 R1（reviewer/fallback/死代码清理）、R2-1~R2-3 ContextPipeline（类型体系 + DefaultContextPipeline + 旧路径删除）、R2-4 Session 版本化、R2-5 MemoryHooks 接口、R2-6 SkillRuntime 接口、R3-1 Bootstrap ContextPipeline 注入、R3-2 SlashCommandRouter+ApprovalConsole、R4-1 Provider 统一类型体系、R4-2 ProviderGateway+RunScope。AgentEngine 从 ~2368 行降至 ~2050 行。
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
