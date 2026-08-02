package com.clawkit.ops.delivery;

import com.clawkit.ops.loop.*;
import com.clawkit.ops.loop.repair.*;
import com.clawkit.provider.LLMConfig;
import com.clawkit.provider.LLMProvider;
import com.clawkit.reliability.attempt.AttemptState;
import com.clawkit.reliability.attempt.FileActionAttemptStore;
import com.clawkit.reliability.gate.RecoveryScanner;
import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.action.FailureClass;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Product-level facade for OPS investigation and repair.
 * OPS-PRODUCT-LOOP-1 V3.
 */
public final class OpsInvestigationFacade implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpsInvestigationFacade.class);

    private final IncidentStore incidentStore;
    private final Clock clock;

    private final InitialReadSessionProvider borrowProvider;
    private final FreshReadSessionFactory freshFactory;
    private final FixSessionFactory fixFactory;
    private final Function<LLMConfig, LLMProvider> providerCreator;

    // ── Session boundary types ──────────────────────────────────────────

    @FunctionalInterface
    public interface InitialReadSessionProvider {
        OpsReadSession borrowConnected(String targetId) throws IOException;
    }
    @FunctionalInterface
    public interface FreshReadSessionFactory {
        OpsReadSession openFresh(String targetId) throws IOException;
    }
    @FunctionalInterface
    public interface FixSessionFactory {
        OpsFixSession openFix(String targetId) throws IOException;
    }

    // ── Approval preparation result (no user interaction yet) ───────────

    private record PreparedApproval(String incidentId, String targetId, String serviceId,
                                     Diagnosis diagnosis, DiscoveryResult precheckDiscovery,
                                     String snapshot,
                                     com.clawkit.tools.action.ActionDescriptor descriptor) {}

    // ── Constructor ─────────────────────────────────────────────────────

    public OpsInvestigationFacade(Path homeDir,
                                   InitialReadSessionProvider borrowProvider,
                                   FreshReadSessionFactory freshFactory,
                                   FixSessionFactory fixFactory,
                                   Function<LLMConfig, LLMProvider> providerCreator) {
        this(homeDir, borrowProvider, freshFactory, fixFactory, providerCreator, Clock.systemUTC());
    }

    public OpsInvestigationFacade(Path homeDir,
                                   InitialReadSessionProvider borrowProvider,
                                   FreshReadSessionFactory freshFactory,
                                   FixSessionFactory fixFactory,
                                   Function<LLMConfig, LLMProvider> providerCreator, Clock clock) {
        this.incidentStore = new IncidentStore(homeDir, clock);
        this.borrowProvider = Objects.requireNonNull(borrowProvider);
        this.freshFactory = Objects.requireNonNull(freshFactory);
        this.fixFactory = fixFactory;
        this.providerCreator = Objects.requireNonNull(providerCreator);
        this.clock = clock;
    }

    // ── Product entry: investigate and optionally repair ────────────────

    public InvestigationView investigateAndMaybeRepair(InvestigationRequest request,
                                                        InvestigationInteraction interaction) {
        String incidentId;
        try {
            incidentId = incidentStore.createIncident(request.targetId(), request.serviceId());
        } catch (IOException e) {
            return emitError(null, request.targetId(), request.serviceId(),
                "无法创建调查记录: " + e.getMessage(), interaction);
        }
        try {
            // Discovery (borrowed session)
            emitProgress(interaction, 1, 4, "正在从 " + request.targetId() + " 收集现场证据……",
                UserIncidentStatus.DISCOVERING);
            updateManifestStatus(incidentId, UserIncidentStatus.DISCOVERING);
            String effectiveSvc = request.serviceId() != null ? request.serviceId() : "order-api";

            DiscoveryResult discovery;
            OpsReadSession discoverySession = borrowProvider.borrowConnected(request.targetId());
            try {
                discovery = new RemoteDiscoveryCoordinator(discoverySession, clock)
                    .collect(incidentId, "run-" + UUID.randomUUID().toString().substring(0, 8),
                        DiscoveryProfile.REMOTE_APP_DOWN_V1);
            } finally { /* never close borrowed */ }

            // Diagnosis
            emitProgress(interaction, 2, 4, "已取得 " + discovery.bundle().evidence().size()
                + " 项证据，正在判断原因……", UserIncidentStatus.DIAGNOSING);
            updateManifestStatus(incidentId, UserIncidentStatus.DIAGNOSING);
            Diagnosis diagnosis = runDiagnosis(discovery, effectiveSvc);
            RemoteIncidentResult result = new RemoteIncidentResult(discovery, diagnosis, true, null,
                clock.instant());
            String report = renderReport(request.targetId(), effectiveSvc, discovery, diagnosis,
                UserIncidentStatus.INCONCLUSIVE);
            incidentStore.updateInvestigationResult(incidentId, result, diagnosisToStatus(diagnosis));
            incidentStore.writeReport(incidentId, report);

            // Non-APP_DOWN → terminal
            if (!"APP_DOWN".equals(diagnosis.rootCauseCode())) {
                UserIncidentStatus terminal = diagnosisToStatus(diagnosis);
                incidentStore.markTerminal(incidentId, terminal);
                return emitView(buildInvestigationView(incidentId, request, discovery, diagnosis,
                    terminal), interaction);
            }

            // Gate check
            RepairSuggestion suggestion = new RepairSuggestion(incidentId, "restart_service",
                "order-api", "order-api container is stopped", diagnosis.confidence(),
                diagnosis.supportingEvidence());
            if (RepairPolicyGate.evaluate(diagnosis, suggestion).denied()) {
                incidentStore.markTerminal(incidentId, UserIncidentStatus.INCONCLUSIVE);
                return emitView(buildInvestigationView(incidentId, request, discovery, diagnosis,
                    UserIncidentStatus.INCONCLUSIVE), interaction);
            }

            if (fixFactory == null) {
                incidentStore.markTerminal(incidentId, UserIncidentStatus.NEEDS_HUMAN);
                return emitView(needsHumanView(incidentId, request.targetId(), effectiveSvc,
                    "发现 order-api 已停止，但当前未配置受限修复通道。"
                    + "请配置 CLAWKIT_REMOTE_FIX_* 环境变量后重试。"), interaction);
            }

            // Enter repair flow: prepare → approve → execute
            PreparedApproval prep = prepareApproval(incidentId, request.targetId(), effectiveSvc,
                diagnosis, interaction, 3);
            if (prep == null) {
                // Error already emitted by prepareApproval
                return new InvestigationView(incidentId, request.targetId(), effectiveSvc,
                    UserIncidentStatus.INCONCLUSIVE, "执行前检查失败",
                    List.of(), "", "", false, "", "",
                    "请重试", clock.instant(), clock.instant(), "");
            }

            ApprovalDecision decision = interaction.requestApproval(
                buildApprovalPrompt(prep));
            if (decision != ApprovalDecision.APPROVE) {
                UserIncidentStatus terminal = decision == ApprovalDecision.REJECT
                    ? UserIncidentStatus.REJECTED : UserIncidentStatus.CANCELLED;
                incidentStore.markTerminal(incidentId, terminal);
                return emitView(rejectedView(incidentId, request.targetId(), effectiveSvc,
                    terminal, diagnosis), interaction);
            }

            return executeApprovedFlow(prep, interaction);

        } catch (Exception e) {
            log.error("[investigation:{}] failed: {}", incidentId, e.getMessage(), e);
            try { incidentStore.markTerminal(incidentId, UserIncidentStatus.INCONCLUSIVE); }
            catch (IOException ignored) { }
            return emitError(incidentId, request.targetId(), request.serviceId(),
                "调查失败: " + e.getMessage(), interaction);
        }
    }

    // ── Phase 1: Prepare approval (no user interaction) ─────────────────

    private PreparedApproval prepareApproval(String incidentId, String targetId, String serviceId,
                                               Diagnosis diagnosis,
                                               InvestigationInteraction interaction,
                                               int stepOffset) {
        emitProgress(interaction, stepOffset, stepOffset + 3, "正在重新检查现场状态……",
            UserIncidentStatus.PRECHECKING);
        updateManifestStatus(incidentId, UserIncidentStatus.PRECHECKING);

        DiscoveryResult precheckDiscovery;
        try (OpsReadSession precheckSession = freshFactory.openFresh(targetId)) {
            precheckDiscovery = new RemoteDiscoveryCoordinator(precheckSession, clock)
                .collect(incidentId, "precheck-" + UUID.randomUUID().toString().substring(0, 8),
                    DiscoveryProfile.REMOTE_APP_DOWN_V1);
        } catch (IOException e) {
            log.error("[precheck:{}] failed: {}", incidentId, e.getMessage());
            return null;
        }

        if (precheckDiscovery.status() != DiscoveryStatus.COMPLETE) {
            try { incidentStore.markTerminal(incidentId, UserIncidentStatus.INCONCLUSIVE); }
            catch (IOException ignored) { }
            emitView(errorView(incidentId, targetId, serviceId,
                "执行前检查失败：无法获取完整的现场证据"), interaction);
            return null;
        }

        Instant now = clock.instant();
        if (checkSelfRecovered(precheckDiscovery, serviceId, now)) {
            try { incidentStore.markTerminal(incidentId, UserIncidentStatus.NO_ACTION_REQUIRED); }
            catch (IOException ignored) { }
            emitView(new InvestigationView(incidentId, targetId, serviceId,
                UserIncidentStatus.NO_ACTION_REQUIRED, "服务已自行恢复，无需重启操作",
                List.of(), formatDiagnosisChinese(diagnosis), "服务已自行恢复",
                false, "", "", "无需操作。详情: /ops inspect " + incidentId,
                clock.instant(), clock.instant(), "incidents/" + incidentId + "/"), interaction);
            return null;
        }

        String snapshot = SnapshotHasher.compute(precheckDiscovery.bundle(), serviceId);
        var descriptor = RepairAction.RESTART_SERVICE.toActionDescriptor(
            "target:" + targetId, serviceId, snapshot);

        return new PreparedApproval(incidentId, targetId, serviceId, diagnosis,
            precheckDiscovery, snapshot, descriptor);
    }

    // ── Phase 2: Execute approved flow (no further approval) ────────────

    private InvestigationView executeApprovedFlow(PreparedApproval prep,
                                                    InvestigationInteraction interaction) {
        String incidentId = prep.incidentId();
        String targetId = prep.targetId();
        String serviceId = prep.serviceId();
        Diagnosis diagnosis = prep.diagnosis();

        ApprovalGrant grant = ApprovalGrant.create(incidentId, "target:" + targetId,
            prep.descriptor(), prep.snapshot(), "cli-user", ApprovalGrant.DEFAULT_TTL);

        // Execute via RepairOrchestrator
        emitProgress(interaction, 5, 6, "正在执行重启操作……", UserIncidentStatus.EXECUTING);
        updateManifestStatus(incidentId, UserIncidentStatus.EXECUTING);

        Path attemptDir = incidentStore.attemptStoreDir(incidentId);
        RepairResult repairResult;
        RepairOrchestrator orchestrator;
        String repairRunId = "repair-" + UUID.randomUUID().toString().substring(0, 8);

        try (FileActionAttemptStore attemptStore = new FileActionAttemptStore(attemptDir)) {
            orchestrator = new RepairOrchestrator(attemptStore, clock);
            try (OpsReadSession freshPrecheckForOrch = freshFactory.openFresh(targetId);
                 OpsFixSession fixSession = fixFactory.openFix(targetId)) {
                repairResult = orchestrator.executeApprovedRepair(grant,
                    RepairAction.RESTART_SERVICE, serviceId, "target:" + targetId,
                    freshPrecheckForOrch, incidentId, fixSession, repairRunId);
            }
        } catch (Exception e) {
            log.error("[repair:{}] execute failed: {}", incidentId, e.getMessage(), e);
            return emitError(incidentId, targetId, serviceId,
                "修复执行失败: " + e.getMessage(), interaction);
        }

        // Verify if appropriate, then persist
        InvestigationView view = verifyAndPersist(incidentId, targetId, serviceId,
            diagnosis, repairResult, repairRunId, attemptDir, interaction);

        // attemptStore already closed by try-with-resources above
        return emitView(view, interaction);
    }

    private InvestigationView verifyAndPersist(String incidentId, String targetId, String serviceId,
                                                 Diagnosis diagnosis, RepairResult repairResult,
                                                 String repairRunId, Path attemptDir,
                                                 InvestigationInteraction interaction) {
        UserIncidentStatus finalStatus;
        String verifySummary = "";
        String actionExecuted = "restart_service(order-api)";
        boolean verificationRetryAllowed = false;

        if (repairResult.attemptState() == AttemptState.VERIFYING
            || repairResult.attemptState() == AttemptState.VERIFICATION_PENDING) {

            emitProgress(interaction, 6, 6, "正在独立验证修复结果……",
                UserIncidentStatus.VERIFYING);
            updateManifestStatus(incidentId, UserIncidentStatus.VERIFYING);

            IndependentVerifier verifier = new IndependentVerifier(clock);
            VerificationResult vr;
            try (OpsReadSession verifySession = freshFactory.openFresh(targetId)) {
                vr = verifier.verify(repairResult, incidentId, verifySession);
            } catch (IOException e) {
                // Transport failure during verification — retry allowed, not terminal
                log.error("[verify:{}] transport failed: {}", incidentId, e.getMessage());
                verificationRetryAllowed = true;
                finalStatus = UserIncidentStatus.NEEDS_HUMAN;
                verifySummary = "验证会话建立失败，可重新尝试验证";
                repairResult = repairResult.withVerification(
                    new VerificationResult("verify-failed-" + repairRunId,
                        repairResult.attemptId(), false, List.of(
                        VerificationResult.VerificationCheck.fail("transport",
                            "verification session failed: " + e.getMessage())),
                        null, false, false));
                // Persist partial result
                persistRepair(incidentId, repairResult, finalStatus, verificationRetryAllowed,
                    "RETRY_VERIFICATION", "使用 /ops continue " + incidentId + " 重新验证");
                return new InvestigationView(incidentId, targetId, serviceId, finalStatus,
                    statusChinese(finalStatus), List.of(), formatDiagnosisChinese(diagnosis),
                    "", false, actionExecuted, verifySummary,
                    "使用 /ops continue " + incidentId + " 重新验证",
                    clock.instant(), clock.instant(), "incidents/" + incidentId + "/");
            }

            boolean allPassed = vr.passed() && vr.businessInvariantsPassed();

            // completeVerification via orchestrator
            try (FileActionAttemptStore store = new FileActionAttemptStore(attemptDir)) {
                RepairOrchestrator orch = new RepairOrchestrator(store, clock);
                repairResult = orch.completeVerification(repairResult, allPassed,
                    vr.passed()
                        ? "independent verification passed: " + vr.checks().size() + " checks"
                        : "verification failed: " + String.join("; ", vr.failureReasons()));
            } catch (Exception e) {
                log.error("[verify:{}] completeVerification failed: {}", incidentId, e.getMessage());
                verificationRetryAllowed = true;
                finalStatus = UserIncidentStatus.NEEDS_HUMAN;
                verifySummary = "验证结果持久化失败，可重试";
                persistRepair(incidentId, repairResult, finalStatus, true,
                    "RETRY_VERIFICATION", "使用 /ops continue " + incidentId + " 重新验证");
                return new InvestigationView(incidentId, targetId, serviceId, finalStatus,
                    statusChinese(finalStatus), List.of(), formatDiagnosisChinese(diagnosis),
                    "", false, actionExecuted, verifySummary,
                    "使用 /ops continue " + incidentId + " 重新验证",
                    clock.instant(), clock.instant(), "incidents/" + incidentId + "/");
            }

            if (repairResult.attemptState() == AttemptState.VERIFIED_SUCCESS) {
                finalStatus = UserIncidentStatus.RESOLVED;
                verifySummary = "独立验证通过：服务进程、HTTP 探测、容器状态正常，无新增错误";
            } else if (repairResult.attemptState() == AttemptState.COMPENSATION_PENDING) {
                finalStatus = UserIncidentStatus.NEEDS_HUMAN;
                verifySummary = "验证未通过：需要人工检查";
            } else {
                verificationRetryAllowed = true;
                finalStatus = UserIncidentStatus.NEEDS_HUMAN;
                verifySummary = "验证未完成，可重新尝试验证";
            }

        } else if (repairResult.attemptState() == AttemptState.OUTCOME_UNKNOWN) {
            finalStatus = UserIncidentStatus.NEEDS_HUMAN;
            verifySummary = "执行结果未知，需要人工检查远端状态";
            actionExecuted = "可能已部分执行";
        } else if (repairResult.attemptState() == AttemptState.FAILED_NO_EFFECT) {
            finalStatus = UserIncidentStatus.FAILED_NO_EFFECT;
            verifySummary = "已确认未产生远端副作用: " + repairResult.detail();
        } else {
            finalStatus = UserIncidentStatus.NEEDS_HUMAN;
            verifySummary = "修复状态: " + repairResult.attemptState();
        }

        String recoveryKind = verificationRetryAllowed ? "RETRY_VERIFICATION"
            : repairResult.attemptState() == AttemptState.OUTCOME_UNKNOWN ? "OUTCOME_UNKNOWN"
            : "NONE";
        String nextAction = finalStatus == UserIncidentStatus.RESOLVED
            ? "问题已恢复。详情: /ops inspect " + incidentId
            : verificationRetryAllowed
                ? "使用 /ops continue " + incidentId + " 重新验证"
                : "需要人工判断。详情: /ops inspect " + incidentId;

        persistRepair(incidentId, repairResult, finalStatus, verificationRetryAllowed,
            recoveryKind, nextAction);

        return new InvestigationView(incidentId, targetId, serviceId, finalStatus,
            statusChinese(finalStatus), List.of(), formatDiagnosisChinese(diagnosis),
            "", false, actionExecuted, verifySummary, nextAction,
            clock.instant(), clock.instant(), "incidents/" + incidentId + "/");
    }

    private void persistRepair(String incidentId, RepairResult repairResult,
                                 UserIncidentStatus status, boolean verificationRetryAllowed,
                                 String recoveryKind, String nextAction) {
        try {
            incidentStore.updateRepairResult(incidentId, repairResult, status);
            incidentStore.updateManifestFields(incidentId,
                "attemptState", repairResult.attemptState().name(),
                "verificationRetryAllowed", String.valueOf(verificationRetryAllowed),
                "recoveryKind", recoveryKind,
                "nextAction", nextAction);
            incidentStore.markTerminal(incidentId, status);
        } catch (IOException e) {
            log.error("[persist:{}] failed: {}", incidentId, e.getMessage());
        }
    }

    // ── Recent / Inspect / ReadReport ───────────────────────────────────

    public List<IncidentSummary> recent(int limit) {
        try { return incidentStore.recent(limit); }
        catch (IOException e) { return List.of(); }
    }

    public InvestigationView inspect(String incidentId) {
        try { return incidentStore.inspect(incidentId); }
        catch (IOException e) { return null; }
    }

    // ── Continue (fail-closed, same incident) ───────────────────────────

    public InvestigationView continueIncident(String incidentId,
                                               InvestigationInteraction interaction) {
        InvestigationView existing = inspect(incidentId);
        if (existing == null) {
            return emitError(incidentId, "", "", "未找到调查记录: " + incidentId, interaction);
        }
        String targetId = existing.targetId();
        String serviceId = existing.serviceId() != null && !existing.serviceId().isEmpty()
            ? existing.serviceId() : "order-api";

        try {
            // Read manifest for recovery metadata
            var manifest = incidentStore.readManifest(incidentId);
            String recoveryKind = manifest != null
                ? manifest.path("recoveryKind").asText("") : "";
            boolean verificationRetryAllowed = manifest != null
                && manifest.path("verificationRetryAllowed").asBoolean(false);

            return switch (existing.status()) {
                case CREATED, DISCOVERING -> reInvestigate(incidentId, targetId, serviceId,
                    interaction);
                case AWAITING_APPROVAL -> reApprove(incidentId, targetId, serviceId, interaction);
                case VERIFYING -> reVerify(incidentId, targetId, serviceId, interaction);
                case NEEDS_HUMAN -> {
                    if (verificationRetryAllowed || "RETRY_VERIFICATION".equals(recoveryKind)) {
                        yield reVerify(incidentId, targetId, serviceId, interaction);
                    }
                    yield recover(incidentId, targetId, serviceId, interaction);
                }
                case RESOLVED -> emitError(incidentId, targetId, serviceId,
                    "问题已恢复，无需继续操作。", interaction);
                case REJECTED, CANCELLED -> emitError(incidentId, targetId, serviceId,
                    "操作已被拒绝或取消，无法继续。", interaction);
                default -> emitError(incidentId, targetId, serviceId,
                    "当前状态不支持继续操作。", interaction);
            };
        } catch (Exception e) {
            log.error("[continue:{}] failed: {}", incidentId, e.getMessage(), e);
            return emitError(incidentId, targetId, serviceId,
                "继续操作失败: " + e.getMessage(), interaction);
        }
    }

    // ── reInvestigate (same incident, read-only, no dispatch ever occurred) ─

    private InvestigationView reInvestigate(String incidentId, String targetId, String serviceId,
                                              InvestigationInteraction interaction) {
        Path attemptDir = incidentStore.attemptStoreDir(incidentId);
        try (FileActionAttemptStore store = new FileActionAttemptStore(attemptDir)) {
            for (var att : store.nonTerminal()) {
                if (att.state() == AttemptState.DISPATCH_INTENT
                    || att.state() == AttemptState.EXECUTION_REPORTED
                    || att.state() == AttemptState.OUTCOME_UNKNOWN) {
                    return emitError(incidentId, targetId, serviceId,
                        "该调查已有进行中的修复操作，不能重新调查。状态: " + att.state(),
                        interaction);
                }
            }
        } catch (Exception e) { /* store may not exist — no writes occurred, safe */ }

        emitProgress(interaction, 1, 3, "重新采集现场证据……", UserIncidentStatus.DISCOVERING);
        try {
            OpsReadSession session = borrowProvider.borrowConnected(targetId);
            DiscoveryResult discovery = new RemoteDiscoveryCoordinator(session, clock)
                .collect(incidentId, "reinvest-" + UUID.randomUUID().toString().substring(0, 8),
                    DiscoveryProfile.REMOTE_APP_DOWN_V1);
            Diagnosis diagnosis = runDiagnosis(discovery, serviceId);
            RemoteIncidentResult result = new RemoteIncidentResult(discovery, diagnosis, true, null,
                clock.instant());
            incidentStore.updateInvestigationResult(incidentId, result,
                diagnosisToStatus(diagnosis));
            incidentStore.writeReport(incidentId,
                renderReport(targetId, serviceId, discovery, diagnosis,
                    UserIncidentStatus.INCONCLUSIVE));

            if (!"APP_DOWN".equals(diagnosis.rootCauseCode())) {
                UserIncidentStatus t = diagnosisToStatus(diagnosis);
                incidentStore.markTerminal(incidentId, t);
                return emitView(buildInvestigationView(incidentId,
                    new InvestigationRequest(targetId, serviceId, null),
                    discovery, diagnosis, t), interaction);
            }

            // Prepare approval ONCE, then execute
            PreparedApproval prep = prepareApproval(incidentId, targetId, serviceId, diagnosis,
                interaction, 2);
            if (prep == null) return emitError(incidentId, targetId, serviceId,
                "执行前检查失败", interaction);

            ApprovalDecision decision = interaction.requestApproval(buildApprovalPrompt(prep));
            if (decision != ApprovalDecision.APPROVE) {
                UserIncidentStatus t = decision == ApprovalDecision.REJECT
                    ? UserIncidentStatus.REJECTED : UserIncidentStatus.CANCELLED;
                incidentStore.markTerminal(incidentId, t);
                return emitView(rejectedView(incidentId, targetId, serviceId, t, diagnosis),
                    interaction);
            }
            return executeApprovedFlow(prep, interaction);

        } catch (Exception e) {
            return emitError(incidentId, targetId, serviceId,
                "重新调查失败: " + e.getMessage(), interaction);
        }
    }

    // ── reApprove (fresh evidence, ONE approval, then execute) ──────────

    private InvestigationView reApprove(String incidentId, String targetId, String serviceId,
                                          InvestigationInteraction interaction) {
        emitProgress(interaction, 1, 3, "重新采集现场证据以确认状态……",
            UserIncidentStatus.DISCOVERING);

        DiscoveryResult discovery;
        try (OpsReadSession session = freshFactory.openFresh(targetId)) {
            discovery = new RemoteDiscoveryCoordinator(session, clock)
                .collect(incidentId, "reapprove-" + UUID.randomUUID().toString().substring(0, 8),
                    DiscoveryProfile.REMOTE_APP_DOWN_V1);
        } catch (IOException e) {
            return emitError(incidentId, targetId, serviceId,
                "采集失败: " + e.getMessage(), interaction);
        }

        if (checkSelfRecovered(discovery, serviceId, clock.instant())) {
            try { incidentStore.markTerminal(incidentId, UserIncidentStatus.NO_ACTION_REQUIRED); }
            catch (IOException ignored) { }
            return emitView(new InvestigationView(incidentId, targetId, serviceId,
                UserIncidentStatus.NO_ACTION_REQUIRED, "服务已自行恢复",
                List.of(), "", "", false, "", "", "无需操作",
                clock.instant(), clock.instant(), "incidents/" + incidentId + "/"), interaction);
        }

        Diagnosis diagnosis = runDiagnosis(discovery, serviceId);
        RepairSuggestion suggestion = new RepairSuggestion(incidentId, "restart_service",
            "order-api", "order-api container is stopped", diagnosis.confidence(),
            diagnosis.supportingEvidence());
        if (RepairPolicyGate.evaluate(diagnosis, suggestion).denied()) {
            try { incidentStore.markTerminal(incidentId, UserIncidentStatus.INCONCLUSIVE); }
            catch (IOException ignored) { }
            return emitView(buildInvestigationView(incidentId,
                new InvestigationRequest(targetId, serviceId, null),
                discovery, diagnosis, UserIncidentStatus.INCONCLUSIVE), interaction);
        }

        // prepareApproval does fresh precheck + snapshot
        PreparedApproval prep = prepareApproval(incidentId, targetId, serviceId, diagnosis,
            interaction, 2);
        if (prep == null) return null;

        // ONE approval
        ApprovalDecision decision = interaction.requestApproval(buildApprovalPrompt(prep));
        if (decision != ApprovalDecision.APPROVE) {
            UserIncidentStatus t = decision == ApprovalDecision.REJECT
                ? UserIncidentStatus.REJECTED : UserIncidentStatus.CANCELLED;
            try { incidentStore.markTerminal(incidentId, t); } catch (IOException ignored) { }
            return emitView(rejectedView(incidentId, targetId, serviceId, t, diagnosis),
                interaction);
        }

        return executeApprovedFlow(prep, interaction);
    }

    // ── reVerify (REAL RepairResult, no fix session, verification only) ─

    private InvestigationView reVerify(String incidentId, String targetId, String serviceId,
                                         InvestigationInteraction interaction) {
        emitProgress(interaction, 1, 2, "重新执行独立验证……", UserIncidentStatus.VERIFYING);

        // Read REAL RepairResult from persisted file
        RepairResult repairResult;
        try {
            repairResult = incidentStore.readRepairResult(incidentId);
        } catch (IOException e) {
            return emitError(incidentId, targetId, serviceId,
                "无法读取修复结果: " + e.getMessage(), interaction);
        }
        if (repairResult == null) {
            return emitError(incidentId, targetId, serviceId,
                "未找到修复结果 (repair-result.json)", interaction);
        }

        // Only allow verify-able states
        AttemptState state = repairResult.attemptState();
        if (state != AttemptState.VERIFYING && state != AttemptState.VERIFICATION_PENDING
            && state != AttemptState.EXECUTION_REPORTED) {
            return emitError(incidentId, targetId, serviceId,
                "当前修复状态 " + state + " 不支持重新验证", interaction);
        }

        // Verify: NO fix session, NO restart call
        IndependentVerifier verifier = new IndependentVerifier(clock);
        VerificationResult vr;
        try (OpsReadSession verifySession = freshFactory.openFresh(targetId)) {
            vr = verifier.verify(repairResult, incidentId, verifySession);
        } catch (IOException e) {
            return emitError(incidentId, targetId, serviceId,
                "验证会话建立失败，请重试: " + e.getMessage(), interaction);
        }

        Path attemptDir = incidentStore.attemptStoreDir(incidentId);
        boolean allPassed = vr.passed() && vr.businessInvariantsPassed();

        try (FileActionAttemptStore store = new FileActionAttemptStore(attemptDir)) {
            RepairOrchestrator orch = new RepairOrchestrator(store, clock);
            RepairResult completed = orch.completeVerification(repairResult, allPassed,
                allPassed ? "re-verification passed"
                    : "re-verification failed: " + String.join("; ", vr.failureReasons()));

            UserIncidentStatus finalStatus;
            String summary;
            boolean retryAllowed = false;
            if (completed.attemptState() == AttemptState.VERIFIED_SUCCESS) {
                finalStatus = UserIncidentStatus.RESOLVED;
                summary = "重新验证通过：服务已恢复";
            } else {
                finalStatus = UserIncidentStatus.NEEDS_HUMAN;
                summary = "重新验证失败";
                retryAllowed = true;
            }

            incidentStore.updateRepairResult(incidentId, completed, finalStatus);
            incidentStore.updateManifestFields(incidentId,
                "attemptState", completed.attemptState().name(),
                "verificationRetryAllowed", String.valueOf(retryAllowed),
                "recoveryKind", retryAllowed ? "RETRY_VERIFICATION" : "NONE",
                "nextAction", finalStatus == UserIncidentStatus.RESOLVED
                    ? "问题已恢复" : "使用 /ops continue " + incidentId + " 重新验证");
            incidentStore.markTerminal(incidentId, finalStatus);

            emitProgress(interaction, 2, 2, summary, finalStatus);
            return emitView(new InvestigationView(incidentId, targetId, serviceId, finalStatus,
                statusChinese(finalStatus), List.of(), "", "", false, "", summary,
                formatNextAction(finalStatus, fixFactory != null),
                clock.instant(), clock.instant(), "incidents/" + incidentId + "/"), interaction);
        } catch (Exception e) {
            return emitError(incidentId, targetId, serviceId,
                "重新验证失败: " + e.getMessage(), interaction);
        }
    }

    // ── recover (RecoveryScanner only, no re-dispatch) ──────────────────

    private InvestigationView recover(String incidentId, String targetId, String serviceId,
                                        InvestigationInteraction interaction) {
        Path attemptDir = incidentStore.attemptStoreDir(incidentId);
        try (FileActionAttemptStore store = new FileActionAttemptStore(attemptDir)) {
            RecoveryScanner.RecoveryReport report = RecoveryScanner.scan(store);
            String summary = String.format(
                "恢复扫描：扫描 %d 项，取消 %d 项，结果未知 %d 项。请人工检查远端状态。",
                report.scanned(), report.cancelledNoEffect(), report.movedToUnknown());
            return emitView(new InvestigationView(incidentId, targetId, serviceId,
                UserIncidentStatus.NEEDS_HUMAN, summary, List.of(), "",
                "需要人工检查远端状态并决定下一步", false, "", "",
                "建议 SSH 登录服务器检查。详情: /ops inspect " + incidentId,
                clock.instant(), clock.instant(), "incidents/" + incidentId + "/"), interaction);
        } catch (Exception e) {
            return emitError(incidentId, targetId, serviceId,
                "恢复扫描失败: " + e.getMessage(), interaction);
        }
    }

    // ── Diagnosis ───────────────────────────────────────────────────────

    private Diagnosis runDiagnosis(DiscoveryResult discovery, String serviceId) {
        if (discovery.status() != DiscoveryStatus.COMPLETE) return inconclusiveDiagnosis();
        try {
            String apiKey = System.getenv("CLAWKIT_API_KEY");
            if (apiKey == null || apiKey.isBlank()) return diagnosticSignalsOnly(discovery);
            String model = System.getenv().getOrDefault("CLAWKIT_DIAGNOSIS_MODEL",
                "deepseek-v4-pro");
            LLMConfig llmConfig = LLMConfig.builder().apiKey(apiKey).model(model).build();
            LLMProvider llmProvider = providerCreator.apply(llmConfig);
            DeepSeekDiagnosisGate gate = new DeepSeekDiagnosisGate(llmProvider, llmConfig.model(),
                clock);
            Diagnosis modelDiagnosis = gate.diagnose(discovery, null, Duration.ofSeconds(120));
            Instant now = clock.instant();
            List<Evidence> currentEvidence = currentEvidence(discovery, now);
            DiagnosticSignals signals = DiagnosticSignals.extract(currentEvidence);
            return DiagnosisReconciler.reconcile(modelDiagnosis, signals, currentEvidence, now);
        } catch (Exception e) {
            log.error("[diagnosis] failed: {}", e.getMessage());
            return diagnosticSignalsOnly(discovery);
        }
    }

    private Diagnosis diagnosticSignalsOnly(DiscoveryResult discovery) {
        Instant now = clock.instant();
        List<Evidence> ce = currentEvidence(discovery, now);
        return DiagnosisReconciler.reconcile(inconclusiveDiagnosis(),
            DiagnosticSignals.extract(ce), ce, now);
    }

    private static List<Evidence> currentEvidence(DiscoveryResult d, Instant now) {
        return d.bundle().evidence().stream()
            .filter(e -> e.collectionStatus() == Evidence.CollectionStatus.OBSERVED)
            .filter(e -> e.freshness() == Evidence.Freshness.CURRENT)
            .filter(e -> e.fact().path("success").asBoolean(false))
            .filter(e -> e.isCurrentAt(now))
            .toList();
    }

    private static Diagnosis inconclusiveDiagnosis() {
        return new Diagnosis("INCONCLUSIVE", 0.0, List.of(), List.of(), List.of(), List.of(),
            "ESCALATE", false);
    }

    // ── Views and helpers ───────────────────────────────────────────────

    private InvestigationView needsHumanView(String incidentId, String targetId, String serviceId,
                                               String summary) {
        return new InvestigationView(incidentId, targetId, serviceId,
            UserIncidentStatus.NEEDS_HUMAN, summary, List.of(), "", "", false, "", "",
            "配置 opsfix 凭据后重试。详情: /ops inspect " + incidentId,
            clock.instant(), clock.instant(), "incidents/" + incidentId + "/");
    }

    private InvestigationView rejectedView(String incidentId, String targetId, String serviceId,
                                             UserIncidentStatus status, Diagnosis diagnosis) {
        return new InvestigationView(incidentId, targetId, serviceId, status,
            statusChinese(status), List.of(), formatDiagnosisChinese(diagnosis),
            "用户未批准修复", false, "", "",
            "未执行任何写操作。详情: /ops inspect " + incidentId,
            clock.instant(), clock.instant(), "incidents/" + incidentId + "/");
    }

    private InvestigationView buildInvestigationView(String incidentId,
                                                      InvestigationRequest request,
                                                      DiscoveryResult discovery,
                                                      Diagnosis diagnosis,
                                                      UserIncidentStatus status) {
        List<String> facts = new ArrayList<>();
        for (Evidence e : discovery.bundle().evidence()) {
            if (e.collectionStatus() == Evidence.CollectionStatus.OBSERVED
                && e.isCurrentAt(clock.instant())) {
                facts.add(formatEvidenceFact(e));
            }
        }
        return new InvestigationView(incidentId, request.targetId(),
            request.serviceId() != null ? request.serviceId() : "",
            status, "对 " + request.targetId() + " 的调查已完成",
            facts, formatDiagnosisChinese(diagnosis),
            formatRecommendation(diagnosis, fixFactory != null),
            status == UserIncidentStatus.AWAITING_APPROVAL,
            "", "", formatNextAction(status, fixFactory != null),
            clock.instant(), clock.instant(), "incidents/" + incidentId + "/");
    }

    private ApprovalPrompt buildApprovalPrompt(PreparedApproval prep) {
        List<String> evidenceSummary = new ArrayList<>();
        for (Evidence e : prep.precheckDiscovery().bundle().evidence()) {
            if (e.collectionStatus() == Evidence.CollectionStatus.OBSERVED
                && e.scope().contains(prep.serviceId())) {
                evidenceSummary.add(e.scope() + ": "
                    + (e.fact().path("success").asBoolean(false) ? "正常" : "异常"));
            }
        }
        return new ApprovalPrompt(prep.incidentId(), prep.targetId(), prep.serviceId(),
            "order-api 已停止运行，业务接口不可用",
            "服务状态和容器状态均显示 order-api 已停止",
            "重启 order-api（restart_service）",
            "仅影响 order-api 服务，不操作数据库或其他服务",
            "低", "重新采集现场证据 + 快照比对，状态变化则自动取消",
            "独立只读会话验证服务状态、HTTP 探测和业务指标",
            "不会操作 postgres，不会执行任意 shell 或 sudo",
            "5 分钟内有效",
            evidenceSummary, prep.descriptor().fingerprint(), prep.snapshot());
    }

    private UserIncidentStatus diagnosisToStatus(Diagnosis d) {
        if ("INCONCLUSIVE".equals(d.rootCauseCode())) return UserIncidentStatus.INCONCLUSIVE;
        if ("APP_DOWN".equals(d.rootCauseCode())) return UserIncidentStatus.AWAITING_APPROVAL;
        if (d.confidence() < 0.5 || d.diagnosisStatus() == Diagnosis.DiagnosisStatus.INCONCLUSIVE)
            return UserIncidentStatus.INCONCLUSIVE;
        if (!d.missingEvidence().isEmpty()) return UserIncidentStatus.INCONCLUSIVE;
        return UserIncidentStatus.NEEDS_HUMAN;
    }

    private boolean checkSelfRecovered(DiscoveryResult discovery, String serviceId, Instant now) {
        for (Evidence e : discovery.bundle().evidence()) {
            if (e.scope().contains(serviceId) && e.type() == EvidenceType.SERVICE_STATUS
                && e.collectionStatus() == Evidence.CollectionStatus.OBSERVED
                && e.isCurrentAt(now) && e.fact().path("success").asBoolean(false)) {
                var containers = e.fact().path("data").path("containers");
                if (containers.isArray() && containers.size() > 0) {
                    String state = containers.get(0).path("State").asText("").toLowerCase();
                    if (!state.contains("exited") && !state.contains("stopped")
                        && !state.contains("down")) return true;
                }
            }
        }
        return false;
    }

    // ── Emit helpers ────────────────────────────────────────────────────

    private InvestigationView emitView(InvestigationView v, InvestigationInteraction i) {
        if (i != null) i.onFinalResult(v);
        return v;
    }
    private InvestigationView emitError(String iid, String tid, String sid, String msg,
                                          InvestigationInteraction i) {
        return emitView(errorView(iid, tid, sid, msg), i);
    }
    private InvestigationView errorView(String iid, String tid, String sid, String msg) {
        return new InvestigationView(iid != null ? iid : "unknown", tid != null ? tid : "",
            sid != null ? sid : "", UserIncidentStatus.INCONCLUSIVE, msg, List.of(), "未完成",
            "", false, "", "", "请重试或使用 /ops recent 查看",
            clock.instant(), clock.instant(), "");
    }
    private void emitProgress(InvestigationInteraction i, int step, int total, String msg,
                               UserIncidentStatus s) {
        if (i != null) i.onProgress(new InvestigationProgress(step, total, msg, s));
    }
    void updateManifestStatus(String incidentId, UserIncidentStatus s) {
        try { incidentStore.appendTimeline(incidentId, "status_update", "ok", "status=" + s); }
        catch (IOException ignored) { }
    }

    // ── Delegated formatters ────────────────────────────────────────────

    static String formatDiagnosisChinese(Diagnosis d) {
        return switch (d.rootCauseCode()) {
            case "APP_DOWN" -> "order-api 当前未运行（服务容器已停止）";
            case "DB_LOCK_WAIT" -> "数据库存在锁等待，影响订单处理";
            case "CPU_PRESSURE" -> "服务器 CPU 使用率异常";
            case "CONNECTION_EXHAUSTION" -> "数据库连接池已耗尽";
            case "INCONCLUSIVE" -> "证据不足，无法确定原因";
            default -> d.rootCauseCode() + "（需人工分析）";
        };
    }
    static String formatRecommendation(Diagnosis d, boolean repairConfigured) {
        if ("APP_DOWN".equals(d.rootCauseCode()))
            return repairConfigured ? "重启 order-api 服务"
                : "发现可修复问题，但当前未配置受限修复通道。请配置 opsfix 凭据后重试。";
        return "建议人工登录服务器检查";
    }
    static String formatNextAction(UserIncidentStatus s, boolean repairConfigured) {
        return switch (s) {
            case AWAITING_APPROVAL -> repairConfigured
                ? "输入 approve 批准重启，或 reject 拒绝"
                : "当前未配置修复通道，请先配置 opsfix 凭据";
            case INCONCLUSIVE -> "建议人工登录服务器检查。详情: /ops inspect <incidentId>";
            case RESOLVED -> "问题已恢复。详情: /ops inspect <incidentId>";
            case NEEDS_HUMAN -> "需要人工判断。详情: /ops inspect <incidentId>";
            case FAILED_NO_EFFECT -> "操作已确认未产生远端副作用。建议人工检查。";
            case NO_ACTION_REQUIRED -> "服务已自行恢复，无需操作。";
            case REJECTED -> "审批被拒绝，未执行任何操作。";
            case CANCELLED -> "操作已取消，未执行任何写操作。";
            default -> "使用 /ops inspect <incidentId> 查看详情";
        };
    }
    static String statusChinese(UserIncidentStatus s) {
        return switch (s) {
            case CREATED -> "已创建"; case DISCOVERING -> "采集中";
            case DIAGNOSING -> "诊断中"; case AWAITING_APPROVAL -> "等待审批";
            case PRECHECKING -> "执行前检查中"; case EXECUTING -> "执行中";
            case VERIFYING -> "验证中"; case RESOLVED -> "已恢复";
            case REJECTED -> "已拒绝"; case CANCELLED -> "已取消";
            case NO_ACTION_REQUIRED -> "无需操作"; case INCONCLUSIVE -> "无法确定";
            case NEEDS_HUMAN -> "需要人工处理"; case FAILED_NO_EFFECT -> "未产生效果";
        };
    }
    static String renderReport(String targetId, String serviceId, DiscoveryResult discovery,
                                Diagnosis diagnosis, UserIncidentStatus status) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 调查结果\n\n**服务器**：").append(targetId)
            .append("\n\n**服务**：").append(serviceId)
            .append("\n\n**状态**：").append(statusChinese(status)).append("\n\n");
        sb.append("## 证据摘要\n\n");
        for (Evidence e : discovery.bundle().evidence()) {
            sb.append("- ").append(e.collectionStatus() == Evidence.CollectionStatus.OBSERVED
                ? "✓" : "✗").append(" **").append(e.scope()).append("** (")
                .append(e.type().name()).append(")\n");
        }
        sb.append("\n## 诊断结论\n\n").append(formatDiagnosisChinese(diagnosis)).append("\n\n");
        if (!"INCONCLUSIVE".equals(diagnosis.rootCauseCode()))
            sb.append("**可信度**：").append(String.format("%.0f%%", diagnosis.confidence() * 100))
                .append("\n\n");
        sb.append("## 建议\n\n");
        sb.append("APP_DOWN".equals(diagnosis.rootCauseCode())
            ? "order-api 已停止运行。可以考虑重启服务。\n\n" : "建议人工检查。\n\n");
        return sb.toString();
    }
    private String formatEvidenceFact(Evidence e) {
        return e.scope() + ": " + (e.fact().path("success").asBoolean(false) ? "正常" : "异常");
    }

    @Override public void close() { incidentStore.close(); }
}
