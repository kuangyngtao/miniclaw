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

截至 2026-07-17（P0-D 本地实现与真实 DeepSeek 联调复核）：

- 全量 `mvn -B -ntp clean verify` 通过：10 个 Reactor 模块、449 项测试，0 Failure、0 Error、0 Skipped；ArchUnit 10/10 硬规则通过，0 violations、0 frozen。
- 四条主链已经收敛：
  - ToolCallExecutor：工具调用唯一入口 ✅
  - ContextPipeline：模型上下文与 compact 唯一入口，主 Agent/SubAgent 均接入 ✅
  - ProviderGateway：模型请求唯一入口，无 raw-provider fallback ✅
  - ApplicationBootstrap：CLI/IM 唯一装配点，运行时依赖构造期注入 ✅
- PlanExecutor 已无状态化，每次执行接收 PlanExecutionContext；权限、审批、recorder、parentRunId 和 Gateway 均随本次运行传递。
- SessionStore、MemoryHooks、SkillRuntime、SlashCommandRouter、ApprovalConsole 已进入生产路径，不再只是类型占位。
- Session 错误、Memory 生命周期和 CLI handler 下沉均已完成：SessionStoreException 携带稳定错误码，DefaultMemoryHooks 接管 recall/extract/save，命令业务已从 ClawkitApp 下沉到 CliCommandHandlers。
- Engine 职责拆分已进入真实路径：WorkspaceStateStore、EngineEventHub、ConversationSession、EngineContextCoordinator、InternalToolSuite、SubAgentRunner、PlanRunCoordinator 分别接管工作区、事件、会话、上下文、内部工具、子 Agent 和 Plan runtime；AgentEngine 保留 ReAct 主循环与公共门面。
- P0-D 的配置、凭据边界、用户可读错误、Windows 启动、示例和分层文档已完成；CI、Docker 和 Release workflow 已实现，仍等待真实 GitHub、镜像和 tag 证据。
- 真实 DeepSeek 文本和工具调用通过；Map 型工具 Schema 丢失结构的问题已修复并加入回归测试。产品仍仅读取 `CLAWKIT_API_KEY`，凭据不进入文件、日志或 diff。

## 当前执行顺序

当前不再扩展新的 Runtime 底层抽象，按退出门禁推进：

1. **D0 / P0-D 外部证据收口**：整理提交、真实 CI、Docker smoke 和首个 Release。
2. **OPS-0A**：App Down 本地只读纵向切片，打通 Fixture、Evidence、Incident、Diagnosis、Evaluator 和 Cleanup。
3. **OPS-0B**：PostgreSQL 锁等待黄金诊断及相同症状不同根因、旧证据、自恢复和 `INCONCLUSIVE` 对抗 Case。
4. **OPS-1**：本地门禁通过后再启用远程只读靶机。
5. **R1 + OPS-2A**：写操作前可靠性门禁满足后，才开放类型化审批修复。

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

- **[~] CI 测试流水线**（ci）
  push/PR 运行 Java 21 全量测试、`git diff --check` 和必要静态检查。
  已新增 Windows Java 21 `clean verify`、提交范围 whitespace 检查、构建后差异检查及 Docker smoke job；本地 workflow 解析和 449 项全量验证通过，待 GitHub push/PR 首次成功运行后转为完成。

- **[~] Dockerfile 与 `.dockerignore`**（distribution）
  构建并运行 shaded jar，明确配置和工作区挂载。
  已提供非 root、JDK/Maven/Git 工具链镜像和 Windows Docker Desktop 挂载说明；本机基础镜像拉取超时，尚无成功 build/smoke 证据。

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
  ✅ 2026-07-17 — 新增 configuration/runtime/mcp/development；将项目状态、Ops 架构、门禁路线和项目叙事分别拆到 project-status/ops-loop-architecture/ops-loop-roadmap/portfolio-story；README 修正实际 JAR、`--im`、Docker 和密钥说明。

- **[~] Release 产物**（ci / distribution）
  tag 自动构建并发布可运行 jar；完成前不承诺稳定发行版。
  已新增 Windows tag 工作流：校验 tag/POM 版本、全量测试、JAR smoke、SHA-256、draft 后发布；当前仍为 SNAPSHOT 且未触发真实 tag，保持部分完成。

### D0：P0-D 外部证据收口

- **[ ] 整理可审查提交**（release）
  将当前 P0-R/P0-D 变更与无关工作区文件分离，确保每个提交的目的和验证证据可解释。
  验收：从干净 clone 可重复执行 README 用户路径；`git status` 不包含无关产物。

- **[ ] 真实 CI 首跑**（ci）
  在 push/PR 上运行 Windows Java 21 全量测试和 Docker smoke。
  验收：GitHub Actions 真实成功；失败时保留日志并修复，不以本地 workflow 解析替代。

- **[ ] Docker 交互 smoke**（distribution）
  网络可用后构建镜像，并以 `docker run --rm -it` 验证 `--help`、`--version`、工作区和 `.clawkit` 挂载。
  验收：镜像由当前提交构建；无 `-it` 时安全失败；容器以非 root 用户运行。

- **[ ] 首个 Release Candidate**（release）
  确定 `v0.1.0` 范围，移除 SNAPSHOT，执行 tag workflow。
  验收：Release 包含可运行 JAR、SHA-256、版本一致性和 Windows 启动说明。

## P1：任务完成率与写操作前可靠性

前置：P0-O/S/R 的关键链路稳定并可度量。OPS-0/OPS-1 的只读诊断不需要等待全部 P1，但任何远程写操作必须先满足 P1-G。

### P1-G：Ops 写操作强制门禁

- **[ ] 取消与预算贯穿**（engine / provider / tools）
  取消信号、deadline、工具次数和 token/时间预算贯穿 ReAct、Plan、SubAgent、Provider 和 Tool。
  验收：取消后不再启动新动作；已启动动作的终态可确认并记录。

- **[ ] 远程结果未知模型**（ops / tools）
  区分执行失败、客户端 timeout、服务端仍执行和结果未知，禁止把 timeout 直接当成无副作用失败。
  验收：结果未知时只允许重新采证、Verification 或人工接管，不自动重复写动作。

- **[ ] Incident Attempt 幂等与恢复**（ops-loop）
  每个写 Attempt 具备 idempotencyKey、次数上限、冷却、预算和目标互斥；进程恢复后识别执行中、结果未知和待验证状态。
  验收：同一 Incident 不并发修复同一目标；重启进程不重复执行已提交动作。

- **[ ] 独立 Verification 隔离**（ops / engine）
  Verification 使用新 runId、新上下文和重新采集的证据，不复用修复 Agent 的结论。
  验收：能识别表面恢复、旧日志误导、业务事务仍失败和数据不一致。

### P1-A：一般可靠性与完成率

- **[ ] 只读工具 session 缓存**：记录命中率，失效规则明确；Ops 证据默认不跨 Incident 缓存。
- **[ ] 工具结果智能截断**：保留 head/tail、错误片段、匹配行、证据引用和截断原因。
- **[ ] 任务感知 compact**：按任务类型保留不同证据，Ops 优先保留时间、来源、反证和未解决假设。
- **[ ] Provider fallback**：超时、限流、熔断后切备用模型并记录指标；不改变当前 DeepSeek-only 交付范围，启用前需单独评审兼容和凭据边界。
- **[ ] 流式早停**：坏协议或明显无效输出中止，不进入工具层。
- **[ ] 失败分类与恢复策略**：区分可重试、需重新采证、需用户输入和不可恢复。

## P2：成本与效率

前置：Benchmark 能证明没有牺牲可靠性和完成率。

- **[ ] 自适应分层 compact**。
- **[ ] 多模型路由**。
- **[ ] Prompt caching**。
- **[ ] 可重置的 Bash session 复用**。
- **[ ] 记忆去重、冲突合并和衰减**。

## P3：Ops Loop 与高级扩展

Ops Loop 按本地只读、黄金诊断、远程只读、审批修复、有限自动化和持续运行推进。只读阶段可在 P1-G 前开展；任何写操作必须等待 P1-G。完整架构和门禁见 [docs/ops-loop.md](docs/ops-loop.md)。

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

- **[ ] Case / Evidence / Evaluation Contract**（ops-fixtures / evaluation）
  先定义隐藏 Ground Truth、必要证据、允许/禁止能力、诊断 schema、确定性断言和一票否决项。
  验收：Clawkit 不可读取 Case ID、注入脚本结果或 Ground Truth；评分不依赖 Agent 自评。

- **[ ] App Down Fixture**（ops-fixtures）
  使用最小 Docker Compose 构造 nginx → demo-api，提供正常态、容器退出注入、幂等清理和外部 HTTP 阈值。
  验收：从全新目录可一条命令建立、注入、评分和清理；连续 10 次初始状态与清理结果一致。

- **[ ] 独立只读 `clawkit-ops-mcp`**（extensions / tools）
  首批只提供 service/container status、ports、HTTP probe 和带时间窗口的有界 logs。
  验收：不存在 `shell_exec` / `ssh_exec(command)`；每个工具声明 schema、readOnly、riskLevel、timeout、output limit 和 audit fields。

- **[ ] 只读 Incident 与报告**（clawkit-ops-loop）
  状态先覆盖 DISCOVERED、COLLECTING、EVIDENCE_READY、DIAGNOSED、INCONCLUSIVE、READ_ONLY_COMPLETE、ESCALATED。
  验收：Evidence 区分事实/推测、observedAt/collectedAt、当前/历史；输出 JSON 与 Markdown，并关联 clawkit runId。

- **[ ] App Down 纵向门禁**（ops / evaluation）
  验收：必要证据覆盖、根因命中和报告生成由 Evaluator 判断；越权、Ground Truth 泄漏和假修复声明均为 0。

### OPS-0B：PostgreSQL 锁等待黄金诊断

- **[ ] 订单业务 Fixture 与 k6 断言**（ops-fixtures）
  构造 nginx → order-api → PostgreSQL，使用固定种子合成订单；k6 验证创建、查询、金额、重复订单、成功率和 P95。
  验收：正常态和清理确定；不用生产数据；业务断言失败时测试返回非零。

- **[ ] 锁与数据库只读证据**（clawkit-ops-mcp）
  提供 PostgreSQL 活动会话、锁、阻塞链和连接统计只读视图，不提供自由 SQL。
  验收：能形成带时间的阻塞链 Evidence Bundle；凭据和连接串不进入模型或报告。

- **[ ] 黄金 Case 与对抗变体**（ops-fixtures / evaluation）
  依次覆盖明确锁等待、相同延迟但不同根因、旧日志、自恢复和未知根因。
  验收：Diagnosis 包含支持证据、反证、候选根因和缺失证据；未知根因返回 `INCONCLUSIVE`；每个 Case 至少 20 次盲测并报告原始计数。

- **[ ] Incident Flight Recorder**（ops / observability）
  按时间线展示症状、证据、假设、排除理由、权限门禁和 Evaluator 结果。
  验收：不复制 Runtime 工具审计事实，以 run/event 引用关联。

### OPS-1：真实只读 SSH 运维

进入条件：OPS-0A/0B 本地门禁通过，Fixture 可幂等重建和清理，D0 的 Docker/Release 可用。

- **[ ] SSH 执行后端**（clawkit-ops-mcp）
  支持主机配置、密钥认证、known_hosts 严格校验、连接/命令 timeout、并发限制、输出截断和结构化错误。
  验收：私钥不进入模型、日志或仓库；测试使用 fake SSH Server/transport；目标主机和命令模板均为 allowlist。

- **[ ] 云服务器只读运维账号**（ops / infrastructure）
  使用非 root、无 sudo 的专用账号，只开放所需日志、状态和健康检查权限；首个远程环境仅运行 fixture/演示服务。
  验收：Agent 无法写文件、重启服务或执行任意命令；越权尝试被工具层和主机权限双重拒绝并审计。

- **[ ] 远程 Discovery Loop**（clawkit-ops-loop）
  手动触发远程巡检，聚合 systemd/container、端口、HTTP 和日志证据，生成 Incident 与诊断报告。
  验收：网络中断、SSH 鉴权失败、部分证据缺失和主机不可达均能分类并升级人工，不误判为业务根因。

### OPS-2A：审批修复、独立验证与补偿

进入条件：P1-G 全部通过；OPS-1 只读 Benchmark 稳定。

- **[ ] Typed Ops Runner 与动作契约**（ops runner / tools）
  首批只提供 `restart_service`、`restore_config`、`cleanup_fixture` 和 `terminate_fixture_session`；禁止任意 sudo/bash/path/SQL。
  验收：动作声明目标枚举、risk、reversibility、idempotencyKey、preconditions、expected effects、verification、compensation、blast radius、cooldown 和 max attempts。

- **[ ] Remediation Skill**（ops / skills）
  先查询 Playbook，再生成最小修复计划；每步包含 fresh precheck、action、verification 和 rollback/compensation。
  验收：全部动作先走 ASK；拒绝后零副作用；状态漂移或结果未知时停止，不自动重复写动作。

- **[ ] 独立 Verification Agent**（ops / engine）
  verifier 使用新上下文和独立采集的证据，不接受修复 Agent 的自证。优先执行确定性断言，再做模型复查。
  验收：至少验证 exitCode、服务状态、端口、HTTP、原故障症状和新增 ERROR；能识别“隐藏日志而非解决问题”的假修复。

- **[ ] 回滚、补偿与不可逆动作门禁**（ops / tools）
  恢复配置和 Release 使用回滚；重启使用补偿；终止带标签 DB 会话属于不可逆但可约束动作。
  验收：每个动作覆盖成功、Precheck 失败、执行失败、结果未知、验证失败、回滚/补偿成功和失败；失败立即升级人工。

### OPS-2B：有限自动修复

- **[ ] 单动作自动化升级策略**（ops / policy）
  自动化按 Action/Playbook 单独提升，不整体切换 AUTO。首个候选仅为 App Down 的 allowlisted 服务重启。
  验收：按 Case、Action、Playbook、模型和版本统计；越权和假修复为 0；连续失败、状态漂移、预算耗尽或补偿失败自动降级为 ASK。

### OPS-3：持续 Loop 与经验复利

- **[ ] Discovery Automation**（ops / scheduling）
  从手动触发升级为 Cron/健康告警触发；同类 Incident 去重并设置冷却窗口。
  验收：连续运行三天不重复轰炸、不并发修复同一目标；支持暂停、取消和预算熔断。

- **[ ] Playbook State**（ops / memory）
  将已验证根因、证据模式、修复与回滚方案写成版本化 YAML；新事件先检索，命中后仍需重新验证前置条件。
  验收：错误或过期 Playbook 不自动执行；记录来源、版本、命中次数、成功率和最后验证时间。

- **[ ] Ops Benchmark 与回归对比**（ops / evaluation）
  指标覆盖 discovery latency、证据覆盖、根因命中、计划可执行率、修复/回滚成功率、越权次数、假修复率、耗时和 token。
  验收：每次 Skill、模型或 Runtime 改动可与 baseline 比较；报告原始成功/失败计数和样本量，退化时阻止自动化等级提升。

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
