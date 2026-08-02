# clawkit TODO

> 状态：`[ ]` 未开始，`[~]` 部分完成或正在迁移，`[x]` 已满足全部验收标准。

本文档只维护当前事实、实施顺序和验收标准。项目边界看 [CLAUDE.md](CLAUDE.md)，稳定工程规范看 [DESIGN.md](DESIGN.md)，文档地图看 [docs/README.md](docs/README.md)，产品方向看 [docs/product-direction.md](docs/product-direction.md)，Ops 目标架构和门禁看 [docs/ops-loop.md](docs/ops-loop.md)。

## 维护规则

- 状态必须由代码和测试证明；创建类、接口或模块不等于完成迁移。
- `[x]` 需要记录完成日期、核心变化和验证方式。
- 一个任务只在一个章节维护；跨章节依赖使用引用，不复制待办。
- 父项只有在全部子项和验收标准满足后才能标记 `[x]`。
- 无法运行验证时保持 `[~]` 或 `[ ]`，并写明原因和残余风险。
- 新发现的问题放入所属主链，不新增“评审遗留问题”堆积区。

## 当前代码事实

截至 2026-08-02：

- 全量回归覆盖 14 个 Reactor 模块并通过；真实网络 E2E 与普通 CI 已分组隔离。具体历史数字只保留在下方验证记录，不用累计测试数量代替产品完成度。
- 新增 `clawkit-reliability` 模块（可靠性内核）+ `clawkit-ops-delivery` 模块（OPS 报告/通知 composition）。
- OPS MVP-2 新增类型：`RemoteIncidentResult`、`RemoteDiscoveryWorkflow`、`BusinessFixtureCase`、`BusinessInvariant`、`FixtureSeed`、`HumanIncidentReport`、`IncidentReportAssembler`、三种 Renderer、`NotificationOutbox`、`OpsFeishuNotifier`、`FeishuApiException`。
- 远端 Fixture 部署脚本：`ops-fixtures/remote/postgres/{install,seed,verify,reset,destroy,run-case}.sh` + `lib/safety-guard.sh`。
- P1-G 七项门禁全部进入真实路径：取消/deadline/预算贯穿 ReAct、Plan、SubAgent、Provider、Tool、ProcessRunner；副作用工具必须生成 ActionDescriptor（无描述符 fail closed）；durable DISPATCH_INTENT 先于执行；结果未知 sticky 禁止自动重复写；MANUAL_REQUIRED 永不自动 VERIFIED_SUCCESS；进程启动恢复扫描 + reconcile；独立 Verification Run 隔离。定版设计与实现对照见 [docs/p1-g-design.md](docs/p1-g-design.md)。
- 四条主链继续收敛：
  - ToolCallExecutor：工具调用唯一入口，且是唯一 Side Effect Gate ✅
  - ContextPipeline：模型上下文与 compact 唯一入口，主 Agent/SubAgent 均接入 ✅
  - ProviderGateway：模型请求唯一入口 + 预算/取消硬拦截点，无 raw-provider fallback ✅
  - ApplicationBootstrap：CLI/IM 唯一装配点 + 启动可靠性恢复扫描 ✅
- PlanExecutor 已无状态化；SessionStore、MemoryHooks、SkillRuntime、SlashCommandRouter、ApprovalConsole 已进入生产路径。
- Engine 职责拆分：AgentEngine 保留 ReAct 主循环与公共门面，Workspace/Event/Session/Context/InternalTool/SubAgent/PlanRun/Verification 全部迁出。
- P0-D 配置、凭据边界、Windows 启动、CI/Docker/Release workflow 已交付；版本 `0.1.0`。
- 远端验证（122.51.51.118）：Fixture 部署成功、HOT_ACCOUNT_CONTENTION_V1 真实锁竞争确认（db_lock_graph 11 行阻塞链、5 活跃 Lock wait）、E2E 20/20 轮全部通过、业务不变量 100 账户全部通过。
- OPS MVP-3 已完成审批修复、fresh precheck、受限执行、独立验证和严格证据包；最终 20/20 `VERIFIED_SUCCESS`，机器汇总 `passed=true`。
- REMOTE-0 交付（2026-07-29）：`clawkit-tools` 新增通用 remote 类型；`clawkit-cli` 完成 Target Store、ConnectionService、自然语言连接、generation-bound 动态工具挂载和完整 ToolExecution 链；服务端/客户端双层脱敏、合同 pin、证据引用、退出清理和 CI E2E 隔离已完成。当前远端 `POSTGRES_DIAGNOSIS_V1` E2E 为 10/10；`APP_DOWN_V1` 五工具真机切换验证作为非阻塞兼容性补测保留。
- PRODUCT-1 已完成 OpenSSH alias 导入、Target onboarding、doctor、状态展示、合同校验和安装引导；本地产品 E2E 与跨模块合同测试通过，真实远端 v2 产品 E2E 作为非阻塞 dogfood 证据保留。
- OPS-PRODUCT-LOOP-1 已把普通 CLI 接到调查、人工审批、受限修复和独立验证主链；本地产品 E2E 13/13 与全量回归通过，里程碑冻结为 `CONDITIONAL_PASS / FROZEN`，等待首次真实远端产品链 dogfood。

## 当前执行顺序

当前不再扩展新的 Runtime 底层抽象，也不继续以工具数量和测试数量推动路线。产品方向见 [docs/product-direction.md](docs/product-direction.md)，当前顺序固定为：

1. **[x] PRODUCT-0：产品方向冻结**：明确目标用户是管理少量 Linux 服务的个人开发者；Remote 是可信入口，Ops Loop 是查看、调查、处置和验证的核心问题解决流程。
2. **[x] PRODUCT-1：服务器接入体验**：已交付 OpenSSH config/Agent/known_hosts 复用、导入向导、doctor、简洁 status 和高级 inspect；普通路径不手写 YAML、key path 或工具 hash。真实远端 v2 产品 E2E 作为非阻塞 dogfood 证据保留。
3. **[~] PRODUCT-2：快速查看与 Ops 调查统一入口**：一句自然语言可以完成 Quick Check；明确调查请求或发现异常时进入现有 `RemoteDiscoveryWorkflow` 和 Incident，不重写 Ops 状态机。
   OPS-PRODUCT-LOOP-1 已交付调查入口、中文报告、Incident 持久化和 recent/inspect/continue 命令。Quick Check 用户任务尚未实现。
4. **[~] PRODUCT-3：审批体验与个人真实使用**：按”发现、建议、原因、影响、执行前保护、执行后验证”展示 MVP-3；连续使用至少 7 天，先修复真实摩擦。
   OPS-PRODUCT-LOOP-1 已交付 JLine 审批、JLineInvestigationInteraction、ApprovalDecision、RepairPolicyGate 集成。真实远端 dogfood 未开始。
5. **[~] D0 / P0-D 外部证据收口**：只保留 Windows `-it` 人工 smoke 和首个 Release，不阻塞 PRODUCT-1/2 的本地开发。
6. **[ ] P2 最小产品度量**：只记录首次连接、首次有效结果、调查耗时、Provider 成本和人工决策等真实使用字段；多模型路由、Prompt caching 等不进入当前主线。
7. **[ ] OPS-3A Observe-only**：在手动调查体验稳定后，推进持续发现、去重、冷却和预算；修复仍保持 ASK。
8. **[ ] OPS-2B Shadow 与有限自治**：只有 PRODUCT-3 的真实使用和 OPS-3A 数据足够后，才为单一固定动作积累 Shadow 数据并评审 Fixture AUTO。

已完成里程碑：OPS-0A、OPS-0B、OPS MVP-1、OPS MVP-2、OPS MVP-3 和 REMOTE-0。它们继续作为产品安全底座和回归基线，不在当前顺序中重复展开。

## PRODUCT：个人运维核心体验

### OPS-PRODUCT-LOOP-1：用户调查、审批修复与独立验证闭环

- **[x] LOOP-1A：只读调查入口**
  新增 `OpsReadSession` 接口（ops-loop）、`RemoteMcpSessionAdapter`（ops-delivery）、`RemoteDiscoveryCoordinator` 改为依赖接口；`OpsInvestigationFacade`、`InvestigationRequest/View/Progress`、`IncidentStore`（`~/.clawkit/incidents/<id>/` manifest + timeline + report.md）、用户状态和中文报告已交付。
  CLI 新增 `OpsCommandParser`（确定性路由，拒绝任意 IP/域名）、`OpsCommandHandler`（/ops investigate/recent/inspect/continue/help）、`JLineInvestigationInteraction`（JLine3 审批）和自然语言路由（仅匹配已登记 target）。clawkit-cli 显式依赖 clawkit-ops-delivery，不直接 import ops-loop。
  验证：全量 `mvn clean verify` 和产品入口 `OpsProductLoopE2ETest` 通过。
  ✅ 2026-08-02 — 编译和全量测试通过。

- **[x] LOOP-1B：审批修复闭环**
  `OpsInvestigationFacade.investigateAndMaybeRepair()` 完整编排：调查 → 策略门禁 → fresh precheck → snapshot → `ApprovalPrompt` → `ApprovalDecision`（APPROVE/REJECT/CANCEL/EOF/INTERRUPTED，永不 null）→ `ApprovalGrant`（5 分钟 TTL）→ `RepairOrchestrator.executeApprovedRepair()` → 独立验证 → 持久化。
  拒绝、取消、EOF 和 Ctrl+C 的产品测试均验证不创建 fix session；真实审批超时未承诺。
  ✅ 2026-08-02 — 本地产品入口和安全拒绝路径通过，真实远端产品链待 dogfood。

- **[x] LOOP-1C：安全恢复与产品 E2E**
  `/ops continue <incidentId>` fail-closed：CREATED/DISCOVERING 在同一 Incident 重新调查；AWAITING_APPROVAL 重新采证并重新审批；验证可重试状态只重新验证；OUTCOME_UNKNOWN 只运行 `RecoveryScanner`；RESOLVED 拒绝继续。
  验证：`OpsProductLoopE2ETest` 13/13、全量 `mvn clean verify` 通过。
  ✅ 2026-08-02

- **[~] 远端产品 E2E / dogfood**：真实远端完整产品链尚未从主 CLI 执行；不再以新增 fake 测试阻塞当前里程碑，首次真实 APP_DOWN 时补充证据并决定是否升级为 PASS。
- **[x] 反方安全评审**：已完成审批重放、重复副作用、验证独立性、session 所有权和 continue 恢复语义检查。
- **[x] 文档更新**：产品方向和 Ops Loop 文档已同步；本里程碑冻结为 `CONDITIONAL_PASS / FROZEN`。

### PRODUCT-0：产品方向冻结

- **[x] 产品定位和目标用户**
  Clawkit 面向用户是本地优先的个人 AI 运维助手，不是通用 Agent Runtime 产品、SSH 管理器或大型 SRE 平台。Runtime 是底座；连接、快速查看、Ops 调查、审批处置和独立验证是一条完整产品旅程。
  ✅ 2026-07-29 — 产品定义、调研、用户旅程、范围和指标写入 [docs/product-direction.md](docs/product-direction.md)，并同步 README、CLAUDE、DESIGN、Ops 和 Remote 文档。

### PRODUCT-1：服务器接入体验

实施切片、安全门禁和反方评审统一见 [docs/product-1-implementation-plan.md](docs/product-1-implementation-plan.md)；本节只维护任务状态和验收结果。

- **[x] 复用 OpenSSH 目标与认证**（cli / tools）
  从用户级、系统级和 Include 形成的 SSH config 图列出 Host alias；静态安全审计通过后，使用 `ssh -G` 获取展开后的 host/user/port/identity/ProxyJump；支持 SSH Agent。Clawkit 只保存逻辑 targetId、SSH alias 和 profile manifest ID，不保存私钥内容或另存 host key 信任状态。
  验收：已有 SSH alias 的用户不重复填写 endpoint；带口令密钥可通过 Agent 使用；Clawkit 强制的禁 PTY/forwarding、strict host key 等安全参数不能被用户配置放宽。
  ✅ 2026-08-02 — OpenSSH alias 解析、安全审计和受控连接参数已进入生产路径并通过机械测试。

- **[x] Target 添加向导与 profile manifest**（cli / tools / ops-mcp）
  `/remote add` 默认进入交互选择或支持 `--from-ssh <alias>`；内置受支持 profile 的 server/protocol/tool contract manifest，普通用户不填写 toolSetHash/contractHash；高级 YAML 导入继续兼容。
  验收：普通路径不手写 YAML、hash、known_hosts 路径和 key path；远端合同漂移仍 fail closed；自定义 profile 不隐式信任。
  ✅ 2026-08-02 — `/remote add --from-ssh`、显式确认/取消、内置 profile catalog 和完整 cross-contract test 已交付。

- **[x] `/remote doctor` 与渐进式状态展示**（cli）
  doctor 检查 OpenSSH 配置、Agent、host key、连接、远端组件和 capability；status 默认只展示目标、连接状态、只读/可写范围和可用能力，inspect 才展示完整 attestation。
  验收：已知错误 100% 展示原因、影响和下一步；所有远程结果显示当前 target；应用退出无 SSH 或工具挂载残留。
  ✅ 2026-08-02 — doctor 分阶段检查、结构化错误、JSON/verbose 输出和退出清理已通过测试。

- **[x] 远端组件安装与撤销引导**（docs / ops-mcp）
  给出可审查、可验证、可撤销的服务器侧安装路径。Clawkit 不静默执行 sudo；安装缺失时 doctor 给出明确步骤。
  验收：从已有 SSH 到第一个只读检查的文档路径可重复；安装和撤销脚本不扩大现有 opsro/opsfix 权限。
  ✅ 2026-08-02 — CLI 首用帮助、既有安装/验证/撤销脚本和安全边界说明已接通；不静默执行 sudo。

### PRODUCT-2：快速查看与 Ops 调查

- **[ ] Quick Check 用户任务**（cli / engine / remote）
  将“看看 order-api 是否正常”“总结最近十分钟错误”组织为用户任务，模型按需调用预定义工具并输出目标、状态、影响、证据时间和下一步。Quick Check 不强制创建 Incident。
  验收：一句自然语言产生带真实 run/tool evidence ref 的摘要；用户不需要知道工具名；无已连接目标时只请求选择或连接，不构造任意 IP。

- **[x] Investigation 产品入口**（cli / ops-loop / ops-delivery）
  明确的“调查/诊断”请求，或 Quick Check 发现满足规则的异常后，进入现有 `RemoteDiscoveryWorkflow`。Incident、Evidence、Diagnosis、Repair 和 Verification 仍留在 OPS，不复制到 CLI。
  验收：一句“调查 test-server 上 order-api 为什么 500”生成持久 Incident；默认按“问题、影响、结论、反证、缺失证据、下一步”展示；证据不足时返回 INCONCLUSIVE。
  ✅ 2026-08-02 — `/ops investigate`、自然语言窄路由、持久 Incident 和中文报告已交付并通过产品 E2E。

- **[x] 最近调查与继续处理**（cli / ops）
  提供最近 Incident、继续最近调查、查看证据和待审批入口；用户默认不需要复制 incidentId，审计层仍使用稳定 ID。
  验收：CLI 重启后可以找到最近调查；多个 Incident 时必须明确选择，不能猜测目标。
  ✅ 2026-08-02 — `/ops recent`、`inspect` 和 fail-closed `continue` 已进入生产路径；继续处理保持同一 Incident。

### PRODUCT-3：审批体验与真实使用

- **[ ] 用户向审批摘要**（cli / ops）
  复用 MVP-3 的 ApprovalGrant、fresh precheck、Attempt 和 Verification，只重排展示：发现、建议、原因、影响、执行前保护、执行后验证。内部 ID、hash 和状态机放入 details。
  验收：拒绝、自恢复、快照漂移、结果未知、验证失败和成功均有明确中文终态；用户可以在 30 秒内做出决定。

- **[ ] 连续 7 天个人 dogfood**（product / evaluation）
  每次真实使用记录任务、用户动作数、耗时、失败点、是否看懂结果和是否需要退回 SSH 手工排查。只修复重复出现的摩擦，不据此扩大生产写权限。
  验收：形成原始使用日志和优先级清单；首次连接、首次有效结果和审批理解指标达到产品方向文档目标后，再启动 OPS-3A/OPS-2B。

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
- 远程业务故障与自动采证：先用确定性注入验证远程链路，再增加由固定合成订单、热点数据分布和真实 HTTP/数据库行为自然诱发的故障；聚合容器、端口、HTTP、日志和必要的数据库只读证据，区分业务故障、数据或负载故障与网络故障。
- 确定性报告聚合：以 Incident、Evidence、Diagnosis、Attempt 和 Verification 为事实来源，生成机器可读 JSON 与人类友好 Markdown；Agent 负责解释，不得改写原始证据。
- 飞书单向通知：使用机器人向固定群发送一屏摘要，并附完整脱敏报告或稳定链接；同一事故、同一报告版本只发送一次。
- 最小权限模型：`OBSERVE` 与 `REMEDIATE_APPROVED` 两个 Capability Profile；授权绑定 Incident、target、action、参数哈希、过期时间和最大次数。
- 一个审批修复闭环：仅在可丢弃 Fixture 中开放 allowlisted `restart_service(serviceId)`，执行前 fresh precheck，执行后独立 Verification，失败或结果未知立即升级人工。

### MVP 明确延后

- 多主机资产管理、云账号自动发现、通用 SSH 连接池、凭据中心、跳板机和 SSH Web 控制台；MVP 后的 REMOTE-0 只实现个人 CLI 的最小已登记 Target。
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
  现有 6×20 数据用于验证采证、协议、权限、报告、评分和清理链路。后续单独评估模型诊断能力时，程序只能校验或否决，不能改写模型的根因、置信度和当前状态；样本必须覆盖证据缺失、冲突、过期、未知根因和未见故障。审批修复落地后，再增加修复闭环验收。

### OPS-1：远程只读运维

这一阶段的目标很简单：本地发起一次诊断，远端只能返回白名单内的只读事实。模型看不到密钥，远端账号不能获得终端，也不能借容器权限取得主机控制权。详细的协议、类名和测试索引放在两份实现文档中：

- [安全架构](docs/ops-mvp1-secure-remote-discovery-design.md)
- [实施与验收合同](docs/ops-mvp1-completion-execution-plan.md)

- **[x] 旧的通用远程命令入口已经退役**
  早期原型允许客户端拼装远端命令，安全边界依赖客户端自律。该路径及其配置、测试已经删除；旧环境变量一旦出现会直接拒绝启动。现在每次诊断都建立一条受控会话，只能调用服务端预先开放的只读能力。

- **[x] 云服务器只读账号已经收口**
  腾讯云测试机上的 `opsro` 已移出容器管理组，无终端、无文件传输、无任意提权，也不能访问容器管理接口。服务端会把所有 SSH 请求导向固定诊断程序，脚本化检查曾取得 38/38 通过。

- **[x] 远端安全接口和会话生命周期已经完成**
  客户端会核对协议版本、能力范围和工具清单，任何一项不一致都立即失败。请求有大小、超时和并发限制；连接中断、协议错误和工具错误会分层记录。一次诊断只保留一个会话，结束时统一关闭，不遗留后台连接。

- **[x] 远程采证和诊断主链已经完成**
  系统按固定顺序采集服务、HTTP、资源、日志和数据库状态。单项失败不会抹掉已经取得的证据；必要证据不足时不调用模型，并明确返回“无法下结论”。输出会先脱敏，再交给模型和报告模块。

- **[x] 业务数据驱动的远程故障**
  已完成固定业务数据、热点账户流量、对账锁竞争、金额守恒检查，以及安装、初始化、运行、核验、重置和销毁脚本。后续又修复了部署路径校验、失败误报、只读数据库凭据和流量分布问题。
  ✅ 2026-07-26 — 远端 20 轮 E2E benchmark 全部通过：20/20 可评估、20/20 正确识别 DB_LOCK_WAIT、零传输故障、前后业务不变量 100 账户全部通过。

- **[x] 人类可读报告**
  已完成统一报告模型，并接入实际交付入口；同一份事实可以输出完整数据、Markdown 报告和飞书摘要。模型不能改写状态、指标或证据，敏感配置不会进入报告。
  ✅ 2026-07-26 — 远端单轮和 20 轮均生成完整报告（JSON/Markdown/飞书摘要），证据引用正确、claimedResolved=false、无密钥泄露。

- **[x] 飞书单向通知**
  已完成固定群发送、后续状态回复、失败重试、持久化待发送队列和并发保护。发送失败只影响通知状态，不会篡改事故结论。
  ✅ 2026-07-26 — Outbox 幂等键 + CAS 保护 + 结构化错误已完成；真实飞书发送留待机器人入群后联调（非阻塞项）。

当前口径：**OPS MVP-2 远程端到端验证 PASS。** 单轮识别 DB_LOCK_WAIT（CONFIRMED），20/20 可评估、20/20 正确诊断、零传输故障、前后业务不变量通过。飞书 Outbox 实现已完成，真实飞书联调为非阻塞延后项。

### OPS-2A：MVP 审批修复与独立验证

进入条件：P1-G 全部通过；OPS-1 只读 Benchmark 稳定。

- **[x] 权限范围与审批门禁**（ops / policy）
  ✅ 2026-07-27 — 20/20 VERIFIED_SUCCESS。模型置信度不能直接触发修复；审批缺失/过期/目标变化/状态漂移/并发执行一律拒绝。

- **[x] 受限动作执行器**（ops runner / tools）
  ✅ 2026-07-27 — opsfix forced-command gateway 逐条校验，二层拒绝；远端脚本部署完成。

- **[x] 独立复验**（ops / engine）
  ✅ 2026-07-27 — IndependentVerifier 新建 opsro 会话，6项检查全通过；假修复识别到位。

- **[x] 修复闭环核心结果**（ops / evaluation）
  ✅ 2026-07-27 — 20轮完整 Java E2E 的结构化 `repair-result.json` 均为 `VERIFIED_SUCCESS`，Incident/Attempt/Repair/Verification ID 独立，业务不变量通过。越权、重复副作用、Journal 顺序和完整汇总由下一项证据补丁单独封板，不再用未测默认值宣称为 0。

- **[x] MVP-3 E2E 证据补丁最终封板**（ops / evaluation）
  ✅ 2026-07-28 — 20 轮完整 Java E2E 的 `overall-summary.json` 为 `passed=true`；20/20 `VERIFIED_SUCCESS`，fresh precheck、snapshot、durable dispatch intent、独立验证、业务不变量和 cleanup 均为 20/20。Incident/Attempt/Repair/Verification ID 全部唯一；duplicate/unauthorized/provider/transport failure 均为 0；profile switch/restore、严格 JSON、Attempt Journal、证据引用和密钥扫描全部通过。

### REMOTE-0：个人 CLI 最小远程连接

实施方案与反方评审：[docs/remote-0-implementation-plan.md](docs/remote-0-implementation-plan.md)。

- **[x] Target 与 Connection 状态切片**（cli / tools / ops adapter）
  Clawkit 仍运行在本地，只连接用户明确登记的一台云服务器。先复用 `RemoteTargetDescriptor`、`SshConnectionConfig`、`RemoteOpsSession` 和现有 SSH/MCP attestation，通过窄接口或 adapter 去除 OPS Delivery 中的硬编码；没有第二个真实消费者前不新建大型基础设施模块。
  验收：CLI 可登记/查看目标、连接/断开、展示 disconnected/connecting/attesting/ready/degraded/failed/closed 状态、延迟、server identity、profile、toolSetHash 和真实工具列表；凭据只保存引用，未知 host key 或 attestation 漂移 fail closed。
  ✅ 2026-07-29 — CLI 主循环、`/remote` 命令、Target Store、ConnectionService、状态快照、退出清理和 OPS 薄适配进入生产路径。

- **[x] 预定义远程只读工具**（tools / mcp）
  第一批仅开放服务/容器状态、端口、HTTP 和有界日志；自然语言可以选择已登记目标并分析结果，但所有远端调用继续进入 ToolRegistry、ToolCallExecutor、权限和 RunEvent 主链。
  验收：服务、路径、时间窗和输出大小由服务端白名单约束；PLAN 可安全使用只读工具；不存在任意 SSH/sudo/Docker/SQL/文件读取入口；连接失败、鉴权失败、profile 漂移和工具错误结构化展示。
  ✅ 2026-07-29 — 严格合同 pin、generation-bound mount、服务端/客户端双层脱敏和 `run://<runId>/tool/<toolCallId>` 证据引用完成。当前远端为 POSTGRES profile；APP_DOWN 五工具真机补测不阻塞实现封板。

- **[x] OPS 适配回归**（ops）
  OPS Loop 改为消费通用 Target/Connection/Capability 窄接口，Incident、Evidence、Diagnosis、Repair 和 Verification 仍保留在 OPS 领域层，不借机重写现有闭环。
  验收：现有远程只读诊断和 MVP-3 合同测试不回归；新增一个非 Incident 的 CLI 场景——连接测试服务器，读取 order-api 有界日志并生成带证据引用的摘要。
  ✅ 2026-07-29 — 14 模块回归通过；非 Incident 远程 E2E 通过完整 Tool 执行链，10/10 测试通过。下一步转入 PRODUCT-1/2，不继续扩展 REMOTE-0 工具范围。

### OPS-2B：有限自动修复

- **[ ] OPS-2B0：单动作 Shadow Policy**（ops / policy）
  **长期方向，当前 No-Go for AUTO。** 自治等级使用 `A0 Observe / A1 Recommend / A2 Ask / A3 Shadow / A4 Limited Auto`；新增版本化 `AutoRemediationPolicy`、`policyHash`、适用环境、Action/target allowlist、预算与持久化降级状态。Shadow 只记录“本可自动执行”的判定，真实修复仍走 ASK；生产路径不得复用 E2E `--auto-approve`。
  首个且唯一候选固定为确定性证据确认的 `APP_DOWN → restart_service(serviceId=order-api)`；模型只负责解释，不参与放宽授权。模型明确反对、证据冲突/缺失/过期、状态漂移、profile 漂移、预算耗尽或结果未知时一律保持/降级 ASK。
  验收：至少 100 次 Shadow 判定，覆盖 APP_DOWN 正例及自恢复、DB_LOCK_WAIT、证据缺失、目标错误、过期证据和传输中断等负例；假阳性修复、越权副作用、重复副作用和未验证成功均为 0。

- **[ ] OPS-2B1：Fixture AUTO 与自动降级**（ops / policy）
  仅在 Fixture 对上述单一 Action/target 晋级 AUTO；`maxAttempts=1`，保留 fresh precheck、TOCTOU、durable intent、目标互斥、结果未知 sticky 和独立 Verification，底层 opsfix 权限不随 AUTO 扩大。
  验收：至少 50 次 Fixture AUTO 全部进入可审计安全终态；任一次 Verification 失败、profile 漂移、预算耗尽、补偿失败或 `OUTCOME_UNKNOWN` 立即持久化降级为 ASK，且不得自动重复写。

- **[ ] OPS-2B2：Canary AUTO 晋级评审**（ops / evaluation）
  仅在 OPS-2B0/2B1 和 OPS-3A 连续运行门禁全部通过后评审；不包含数据库会话终止、Release 切换、其他服务或任意 shell/sudo。
  验收：按 Case、Action、Playbook、模型、policyHash 和版本报告原始计数与样本量；越权和假修复始终为 0，Verification 生成率 100%，具备一键全局降级 ASK。

### OPS-3：持续 Loop 与经验复利

- **[ ] OPS-3A：Observe-only Discovery Automation**（ops / scheduling）
  **2026-07-27 重评结论：先于 Fixture AUTO 落地，但只自动发现、诊断、报告和生成建议，修复仍保持 ASK。** 从手动触发升级为 Cron/外部 Probe/健康告警触发；新增持久化 Incident Registry、fingerprint 去重、ACTIVE Incident 合并、关闭后冷却、同目标 Discovery 互斥、暂停/取消、deadline、Provider/Discovery 日预算和重启恢复。
  验收：仅在 Fixture 连续运行 72 小时；不重复轰炸、不并发处理同一目标，暂停后不创建新任务，重启后不重复通知，预算耗尽后停止 Provider 调用，进行中的写 Attempt 不被重新派发。

- **[ ] OPS-3B：Playbook State**（ops / memory）
  在 OPS-3A 稳定后，将已验证根因、证据模式、修复与回滚方案写成版本化 YAML；记录 `schemaVersion`、`policyHash`、来源、适用环境、失效/撤销规则、命中次数、ASK/AUTO 成功率和最后验证时间。
  新 Incident 先检索 Playbook，但命中后仍必须重新采证、fresh precheck 和 TOCTOU 校验；Playbook 不得扩大 gateway、MCP profile 或主机权限。
  验收：错误、冲突、撤销或过期 Playbook 不自动执行；版本/策略变化可追溯并自动降级 ASK。

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

- 2026-07-28：**OPS MVP-3 PASS — 20/20 VERIFIED_SUCCESS，全部27个门禁满足。**
  证据目录：`D:\tmp\e2e-out\run-20260728T140908Z`。overall-summary.json: requested=20, started=20, completed=20, verifiedSuccess=20, freshPrecheckPassed=20, snapshotMatched=20, dispatchIntentPersisted=20, independentVerificationPassed=20, businessInvariantsPassed=20, cleanupPassed=20, all failures=0, duplicateSideEffects=0, unauthorizedSideEffects=0, verifyOpsfixPassed=true, profileSwitchPassed=true, profileRestorePassed=true, allRoundIdsUnique=true, attemptJournalPassed=true, allJsonStrictlyValid=true, allEvidenceMeasured=true, secretsDetected=0, passed=true。mvn clean verify 121 classes/0 failures/0 errors。git diff --check PASS。
  最终证据补丁使用真实生命周期 observer、严格 MCP attestation、CRC Journal 解析、唯一 ID、顺序状态、证据引用和密钥扫描生成机器结论；早期 `passed=false` 的中间证据包只作为历史问题，不再代表最终状态。
- 2026-07-29：**REMOTE-0 IMPLEMENTATION_PASS。**
  CLI 主链、严格合同、generation-bound mount、双层日志脱敏、真实 evidence ref、退出清理、完整 Tool 执行链 E2E 和 CI 分组隔离完成；14 模块回归通过，远端 POSTGRES profile E2E 10/10。APP_DOWN_V1 五工具真机切换验证作为非阻塞兼容性补测保留，不为补测主动扰动当前远端 profile。
- 2026-07-29：**产品方向重定。**
  Clawkit 面向用户定位为本地优先的个人 AI 运维助手。Remote 是服务器接入和可信能力入口，Ops Loop 是快速查看之后的证据化调查、审批处置和独立验证核心。近期优先 PRODUCT-1 接入体验、PRODUCT-2 调查入口和 PRODUCT-3 审批/dogfood，不继续按工具数量扩展。
- 2026-07-26：**OPS MVP-1 远程只读链路通过。**
  腾讯云测试机已完成账号收权、固定入口部署、协议握手、五项只读能力调用和安全负例检查。终端、文件传输、任意命令、容器管理接口和提权均被拒绝；机械化检查为 38/38 通过，相关 134 项自动化测试通过。撤销账号的脚本尚未在真机执行，留到审批修复阶段统一验证。
- 2026-08-01：**OPS-PRODUCT-LOOP-1 V3 收口（CONDITIONAL_PASS）。**
  全量非 E2E 测试：15 模块，0 失败/0 错误；新增 OpsProductLoopE2ETest（13 测试，9 PASS/4 待远端验证）。
  核心修复：
  - prepareApproval/executeApprovedFlow 分离：任何用户操作最多一次审批提示
  - reVerify 读取真实 RepairResult（IncidentStore.readRepairResult），不伪造，不创建 fix session
  - Manifest 持久化 recoveryKind/verificationRetryAllowed/attemptState/nextAction
  - FileActionAttemptStore 全路径 try-with-resources
  - OpsFixSession 移除 final + 新增 public test constructor（transport+client 注入）
  - Fix target descriptor 使用完整 5 字段（profile + probeVersion + toolSetHash + toolContractHash）
  - 删除 ApprovalDecision.TIMEOUT（不实现虚假枚举）
  - IncidentStore 新增 readRepairResult/readManifest/updateManifestFields
  - VERIFYING continue 仅重新验证，NEEDS_HUMAN+verificationRetryAllowed continue 可安全重新验证
  - OUTCOME_UNKNOWN continue 仅 RecoveryScanner，绝不重新 dispatch
  待完成：真实远端 E2E（PASS 门禁）、修复 4 个 E2E 测试断言。

- 2026-07-25：**v0.1.0 交付路径修复与 Runtime 基准收口** — 修复 README clone 地址（`kuangyngtao/miniclaw`）、Dockerfile 补齐 OPS 模块 POM；新增 `scripts/package-release.ps1` 统一打包脚本；Release workflow 调用统一脚本并增加 `--generate-notes` 和 draft-then-publish。Benchmark fingerprint 从 `hashCode()` 迁移到 SHA-256；Scorer 增加稳定描述符（`stableId+version+canonicalConfig`）；`BenchmarkMain run` 永不写入 baseline、`compare` 要求 baseline 存在、`baseline --output` 禁止覆盖正式 baseline。CI 增加 Runtime baseline compare 回归门禁。生成并提交 `runtime-v1.json`（16 case、13 PASS、3 FAIL 为已有行为）；冻结 OPS-0B 6×20 历史证据到 `benchmarks/evidence/ops-0b-pipeline-20260722.json`。全量 `mvn clean verify` 12 模块通过；Docker `--help`/`--version`/UID=10001/C-007 退出码 2 均通过。**本轮未运行 OPS-0B 6×20。**
- 2026-07-22：**完成远程连接原型。**
  该原型证明密钥连接可行，但把容器管理权限交给只读账号，安全边界不成立。7 月 26 日已用服务端固定入口替换，并删除原型代码。
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
- 2026-07-26：**OPS MVP-2 远程端到端验证 PASS。**
  单轮 E2E 正确识别 DB_LOCK_WAIT（CONFIRMED），20/20 可评估、20/20 正确诊断、零传输故障。核心变更：接入 DiagnosisReconciler（确定性 DiagnosticSignals → rootCauseCode，DeepSeek 负责解释/引用/备选）；McpClient JSON-RPC error 不再抛 IOException；order-api 固定端口映射 127.0.0.1:18080；权限分离（opsro MCP / admin fixture / control token 环境变量）；McpClient 合同测试 9 条。全量 mvn clean verify 14 模块通过，git diff --check 通过。提交索引为 `b21ad1a` 至当前（含 DiagnosisReconciler 接线、E2E 强化、权限分离）。
- 2026-07-11：O1 完成 — 4 PR / 186 测试全部通过（observability 71 + engine 69 + cli 46）。两文件契约（events.jsonl + summary.json）落地，metrics 改为 events 投影，不再持久化 metrics.jsonl。
- 2026-07-11：文档记录的 Maven 汇总为 295 个测试通过；本记录仅作为基线，实际合入前必须重新运行相关测试。
- 2026-07-11：TODO/CLAUDE/DESIGN 按”纲领 / 稳定设计 / 执行路线”重新分工；当前完成状态已按代码事实降级或重排。
- 2026-07-11：补充远程运维 Ops Loop 路线，按本地 fixtures、只读 SSH、审批修复、独立验证和持续调度分阶段实施；代码修复 Loop 明确不在当前范围。
- 2026-07-12：P0-S 全部 8 PR + 第二轮修复完成。66 工具测试 + 9 PermissionModeTest + 17 RunAccumulatorTest + 91 引擎/观测/上下文/记忆测试全部通过（0 failures），全项目 clean compile 通过。

  **第一轮（PR 1-6）**：契约 V2（8 新类型 + 4 核心类重构）、8 内置工具显式 metadata、PermissionPolicy + ApprovalGrantCache + ASK fail-closed、MCP annotations/trusted/isError/坏参数保护、ProcessRunner + BashTool 重写、WorkspacePathPolicy + WriteTool overwrite + 原子写入。

  **第二轮（P0 阻断修复）**：internal tools 走 PermissionPolicy、SubAgent 删除特判分支统一走 ToolCallExecutor、metadataFor() 用 registry.lookup() + isReadOnly() 回退、Plan recorder null guard、PlanExecutor 两调用点迁移到 ToolCallExecutor。

  **第二轮（P1 生命周期修复）**：BashTool 覆盖 execute(ToolExecutionRequest) → TIMED_OUT/NON_ZERO_EXIT/exitCode/outputStats、WriteTool requireWriteAccess 处理新文件、MCP annotations 缺失字段用保守默认值（fieldBool）、ProcessRunner 进程树先快照再终止 + exitValue try-catch、ToolMetadata compact constructor 验证平铺字段与 ToolBehavior 一致、RunAccumulator 审批指标修正（NOT_REQUIRED 不计入 approvalRequested）。

  **已删除**：engine RiskLevel.java、McpAuditLogger.java。engine main 源码中无活跃的 registry.execute() 调用。
