# Phase 0 Baseline: Fixture Design and Test Matrix

> 日期：2026-08-03
> 状态：Phase 0 complete — 基线确立、夹具模型和测试矩阵就绪

## 1. 文档与代码一致性

核对结果：TODO.md、docs/product-direction.md、代码三者一致。

| 文档 | PRODUCT-3 状态 | 证据 |
|------|---------------|------|
| TODO.md | `[x]` 拒绝零副作用闭环, `[ ]` 其余全部 | AppDownRejectFixtureTest (6 tests), RejectedTerminalRenderTest (8 tests) |
| product-direction.md §10 | "部分完成；本地 CLI 审批、受限修复和独立验证已接通" | OpsInvestigationFacade.investigateAndMaybeRepair() 完整管线存在 |
| 代码 | 拒绝/取消路径已验证；批准路径代码存在但未经夹具测试 | executeApprovedFlow() 和 RepairOrchestrator 已实现但无 Fixture 覆盖 |

结论：文档不需要修正。现有基线准确。

## 2. 关键入口定位

### 2.1 批准路径（approve → execute）

```
OpsInvestigationFacade.investigateAndMaybeRepair()          [L90-181]
  ├─ discovery (borrowed read session)                       [L106-112]
  ├─ diagnosis (DeepSeekDiagnosisGate → DiagnosisReconciler) [L118]
  ├─ RepairPolicyGate.evaluate()                             [L138]
  ├─ fixFactory == null → NEEDS_HUMAN                        [L144-149]
  ├─ prepareApproval()                                       [L152-160]
  │   ├─ freshFactory.openFresh() — 新建只读会话              [L194]
  │   ├─ RemoteDiscoveryCoordinator.collect() — 重新采证     [L195-197]
  │   ├─ checkSelfRecovered() — 自恢复检查                    [L212]
  │   ├─ SnapshotHasher.compute() — 快照                      [L223]
  │   └─ RepairAction.RESTART_SERVICE.toActionDescriptor()    [L224-225]
  ├─ interaction.requestApproval(buildApprovalPrompt())       [L162-163]
  ├─ if REJECT/CANCEL → rejectedView() → terminal             [L164-170]
  └─ executeApprovedFlow()                                    [L172]
      ├─ ApprovalGrant.create() — 5 min TTL                   [L240-241]
      ├─ fixFactory.openFix() — 修复会话                       [L255]
      ├─ RepairOrchestrator.executeApprovedRepair()            [L256-257]
      │   ├─ fresh precheck (new opsro session)                [RO L61-91]
      │   ├─ SnapshotHasher → grant.validate() TOCTOU          [RO L95-105]
      │   ├─ ActionAttemptCoordinator.begin()                  [RO L110]
      │   ├─ coordinator.completePrecheck()                    [RO L119]
      │   ├─ coordinator.markDispatchIntent() durable           [RO L129]
      │   ├─ fixSession.executeRestart()                       [RO L143]
      │   ├─ coordinator.reportOutcome()                       [RO L155]
      │   └─ coordinator.startVerification()                   [RO L163]
      └─ IndependentVerifier.verify()                          [L293]
```

### 2.2 独立验证

```
IndependentVerifier.verify(repairResult, incidentId, OpsReadSession)  [IV L97-151]
  ├─ RemoteDiscoveryCoordinator.collect() — 新只读会话采证  [IV L110-112]
  ├─ checkServiceStatus() → EvidenceReader.isRunning()        [IV L154-171]
  ├─ checkContainerStatus() → EvidenceReader.isRunning()      [IV L173-191]
  ├─ checkHttpReadiness() → EvidenceReader.dataHttpStatus()   [IV L193-221]
  ├─ checkAppDownResolved() — 无剩余 failure                   [IV L223-236]
  ├─ checkNoNewErrors() — 日志无 ERROR                         [IV L238-253]
  └─ checkBusinessInvariants() — 指标或 service+HTTP OK       [IV L255-290]
```

### 2.3 EvidenceReader 状态提取（关键路径）

```
EvidenceReader.state(e)                                       [ER L117-145]
  ├─ data.containers[0].State (service_status via docker ps)
  ├─ data.state.Status (container_status lower)
  ├─ data.State.Status (container_status capital)
  ├─ data.Status (flat)
  └─ data.State (flat fallback)

EvidenceReader.isRunning(e)  → state().contains("running")    [ER L148-150]
EvidenceReader.isStoppedOrExited(e) → state().contains("exited"/"stopped"...) [ER L153-156]
EvidenceReader.dataHttpStatus(e) → data.statusCode or data.status [ER L97-109]
EvidenceReader.success(e) → fact.success                      [ER L32-34]
```

**重要发现**: `DiagnosticSignals.extract()` 和 `DiagnosisReconciler.detectAppDown()` 不通过 `EvidenceReader`，直接用 `data.path("State")`。这意味着它们只匹配 `EvidenceReader.state()` 的第 5 分支（flat State）。夹具的 `AppDownReadSession` 返回 `{"State":"exited"}` 兼容此路径。

### 2.4 JLine 终端渲染

```
JLineInvestigationInteraction.onFinalResult()                 [L105-152]
  ├─ REJECTED/CANCELLED → formatRejectedTerminal()             [L107-110]
  ├─ RESOLVED → "✓ 问题已恢复"
  ├─ INCONCLUSIVE → "? 无法确定原因"
  └─ AWAITING_APPROVAL → "⚠ 需要审批修复操作"

JLineInvestigationInteraction.requestApproval()               [L37-102]
  └─ 展示 ApprovalPrompt 全部字段 → 等待 [approve/reject/cancel]

OpsCommandHandler.cmdInspect()                                [L109-151]
  └─ facade.inspect(incidentId) → InvestigationView → 格式化输出
```

### 2.5 安全门禁清单（不得削弱）

| # | 门禁 | 位置 | 验证方式 |
|---|------|------|---------|
| G1 | fixFactory.openFix() 仅在 executeApprovedFlow 内调用 | OpsInvestigationFacade L255 | 夹具计数器 = 0 |
| G2 | ApprovalGrant 5min TTL | ApprovalGrant.DEFAULT_TTL | 时间边界测试 |
| G3 | TOCTOU 快照比对 | RepairOrchestrator L95-105 | 漂移夹具 |
| G4 | fresh precheck 在每次执行前 | RO L61-91 | 计数器 |
| G5 | durable DISPATCH_INTENT | RO L129 | Attempt journal |
| G6 | 结果未知 sticky → 禁止自动重试 | AttemptState.OUTCOME_UNKNOWN | 夹具 |
| G7 | 独立 Verification 使用新会话 | IV L110-112 | 会话计数器 |
| G8 | 写动作仅限 restart_service(order-api) | RepairAction.RESTART_SERVICE | 参数断言 |

## 3. 夹具状态模型

### 3.1 会话类型和可控状态

```
FixtureSessionFactory
  ├─ BorrowReadSession    — 初始采证（固定 APP_DOWN）
  ├─ FreshReadSession     — precheck 采证（可切换状态）
  │   ├─ APP_DOWN         — containers[0].State=exited, http_probe→503
  │   ├─ RUNNING          — containers[0].State=running, http_probe→200
  │   └─ DETERIORATED     — containers[0].State=exited + 新错误
  ├─ VerificationSession  — 独立验证采证（可切换状态）
  │   ├─ RUNNING_HEALTHY  — running + healthy + HTTP 200 + no errors
  │   └─ STILL_DOWN       — 仍为 exited + HTTP 503
  └─ FixSession           — 修复操作（不执行真实 SSH）
      ├─ SUCCESS          — returns EffectCertainty.EFFECT_CONFIRMED
      ├─ FAIL_NO_EFFECT   — returns FAILED_NO_EFFECT
      └─ TIMEOUT          — throws IOException
```

### 3.2 写操作计数器

```
WriteCounters
  ├─ fixSessionCreated    — fixFactory.openFix() 调用次数
  ├─ restartCalls         — executeRestart() 调用次数
  ├─ freshPrecheckCount   — freshFactory.openFresh() 调用次数
  └─ verifySessionCount   — verification session 创建次数
```

## 4. 测试矩阵

### 4.1 状态 → 预期终态

| # | 初始 | 审批 | Precheck | 执行 | 验证 | 预期终态 | 写计数 |
|---|------|------|----------|------|------|---------|--------|
| P1 | APP_DOWN | APPROVE | APP_DOWN | SUCCESS | RUNNING_HEALTHY | RESOLVED | fix=1, restart=1 |
| P2 | APP_DOWN | REJECT | N/A | N/A | N/A | REJECTED | fix=0, restart=0 |
| P3 | APP_DOWN | CANCEL | N/A | N/A | N/A | CANCELLED | fix=0, restart=0 |
| S1 | APP_DOWN | APPROVE | RUNNING | N/A | N/A | NO_ACTION_REQUIRED | fix=0, restart=0 |
| S2 | APP_DOWN | APPROVE | DETERIORATED | N/A | N/A | CANCELLED | fix=0, restart=0 |
| E1 | APP_DOWN | APPROVE | APP_DOWN | FAIL_NO_EFFECT | N/A | FAILED_NO_EFFECT | fix=1, restart=1 |
| E2 | APP_DOWN | APPROVE | APP_DOWN | TIMEOUT | N/A | OUTCOME_UNKNOWN | fix=1, restart=1 |
| V1 | APP_DOWN | APPROVE | APP_DOWN | SUCCESS | STILL_DOWN | NEEDS_HUMAN | fix=1, restart=1 |

### 4.2 安全断言矩阵

| 场景 | fix=0 | restart=0 | TOCTOU | Durable | 独立验证 | /ops inspect | /ops continue |
|------|-------|-----------|--------|---------|---------|-------------|--------------|
| P1 APPROVE | ✗ | ✗ | ✓ | ✓ | ✓ | RESOLVED | blocked |
| P2 REJECT | ✓ | ✓ | N/A | N/A | N/A | REJECTED | blocked |
| P3 CANCEL | ✓ | ✓ | N/A | N/A | N/A | CANCELLED | blocked |
| S1 自恢复 | ✓ | ✓ | ✓ | N/A | N/A | NO_ACTION | blocked |
| S2 漂移 | ✓ | ✓ | ✓ | N/A | N/A | CANCELLED | blocked |
| E1 执行失败 | ✗ | ✗ | ✓ | ✓ | N/A | FAILED | 人工 |
| E2 结果未知 | ✗ | ✗ | ✓ | ✓ | N/A | OUTCOME_UNKNOWN | sticky |
| V1 验证失败 | ✗ | ✗ | ✓ | ✓ | ✓ | NEEDS_HUMAN | 重试验证 |

## 5. 安全门禁不变证明

所有现有门禁在夹具测试中通过以下方式保持完整：

1. **fixFactory 调用路径不变** — 夹具仅在 executeApprovedFlow 内创建 fix session，与生产路径一致
2. **ApprovalGrant.validate() 继续被调用** — RepairOrchestrator L99 不变
3. **TOCTOU 快照比对不变** — SnapshotHasher + validate 路径不变
4. **Attempt journal 不变** — ActionAttemptCoordinator 使用 FileActionAttemptStore 不变
5. **结果未知 sticky 不变** — OUTCOME_UNKNOWN 状态不可自动转换为成功
6. **独立 Verification 使用新会话** — IndependentVerifier 通过 freshFactory 获取新会话
7. **预定义动作不变** — 仅 restart_service(order-api)，不新增第二个写动作

## 6. 阶段 0 产物清单

- [x] 文档与代码一致性核对 — 一致，无需修正
- [x] 全部关键入口定位 — 见 §2
- [x] 夹具状态模型 — 见 §3
- [x] 测试矩阵 — 见 §4
- [x] 安全门禁不变证明 — 见 §5
- [ ] TODO.md 基线更新 — 待写入

## 7. 下一步：阶段 1

进入阶段 1 实现 P1（批准成功闭环）：
- 创建 `AppDownApproveFixtureTest`
- 实现可切换状态的 `FixtureSessionFactory`
- `FixSession` 的 fake 实现（不执行真实 SSH）
- RESOLVED 终态渲染验证
- JLine 成功终态冒烟测试
