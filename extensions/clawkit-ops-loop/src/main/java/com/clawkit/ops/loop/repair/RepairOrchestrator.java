package com.clawkit.ops.loop.repair;

import com.clawkit.ops.loop.*;
import com.clawkit.reliability.attempt.*;
import com.clawkit.reliability.attempt.ActionAttemptCoordinator.AttemptTicket;
import com.clawkit.tools.action.*;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RepairOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RepairOrchestrator.class);
    private final ActionAttemptCoordinator coordinator;
    private final Clock clock;
    private final RepairLifecycleObserver observer;

    public RepairOrchestrator(FileActionAttemptStore store, Clock clock) {
        this(store, clock, RepairLifecycleObserver.NOOP);
    }
    public RepairOrchestrator(FileActionAttemptStore store) {
        this(store, Clock.systemUTC());
    }
    public RepairOrchestrator(FileActionAttemptStore store, Clock clock,
                               RepairLifecycleObserver observer) {
        this.coordinator = new ActionAttemptCoordinator(store,
            new ActionAttemptCoordinator.AttemptPolicy(1, Duration.ofSeconds(30)), null);
        this.clock = clock;
        this.observer = observer != null ? observer : RepairLifecycleObserver.NOOP;
    }

    public RepairSuggestion generateSuggestion(Diagnosis diagnosis, DiscoveryResult discovery) {
        Objects.requireNonNull(diagnosis, "diagnosis required");
        if (!"APP_DOWN".equals(diagnosis.rootCauseCode())) {
            return new RepairSuggestion(discovery != null ? discovery.incidentId() : "unknown",
                "restart_service", "order-api",
                "Not applicable: root cause is " + diagnosis.rootCauseCode(), 0.0, List.of());
        }
        return new RepairSuggestion(discovery != null ? discovery.incidentId() : "unknown",
            "restart_service", "order-api",
            "order-api container is stopped; restart_service(order-api) is the only allowed repair action",
            diagnosis.confidence(), diagnosis.supportingEvidence());
    }

    public RepairResult executeApprovedRepair(
            ApprovalGrant grant, RepairAction action, String serviceId,
            String canonicalTarget, OpsReadSession opsroSession,
            String incidentId, OpsFixSession fixSession, String runId) throws IOException {

        var seq = new AtomicInteger(0);

        emit(seq, runId, incidentId, null, "fresh_precheck_started", "in_progress", "new opsro session");
        DiscoveryProfile profile = DiscoveryProfile.REMOTE_APP_DOWN_V1;
        RemoteDiscoveryCoordinator precheckCoord = new RemoteDiscoveryCoordinator(opsroSession);
        DiscoveryResult precheckDiscovery = precheckCoord.collect(incidentId, "precheck-" + runId, profile);
        EvidenceBundle freshEvidence = precheckDiscovery.bundle();
        Instant now = clock.instant();
        boolean evidenceSufficient = precheckDiscovery.status() == DiscoveryStatus.COMPLETE;
        boolean evidenceCurrent = freshEvidence.evidence().stream()
            .filter(e -> e.collectionStatus() == Evidence.CollectionStatus.OBSERVED)
            .allMatch(e -> e.isCurrentAt(now));

        if (!evidenceSufficient || !evidenceCurrent) {
            emit(seq, runId, incidentId, null, "fresh_precheck_passed", "failed",
                "complete=" + evidenceSufficient + " current=" + evidenceCurrent);
            return failNoEffect(grant.incidentId(), "precheck-failed-" + runId, runId,
                "Fresh precheck: complete=" + evidenceSufficient + " current=" + evidenceCurrent);
        }
        if (!EvidenceReader.find(freshEvidence, EvidenceType.SERVICE_STATUS, "order-api", now).isPresent()
            || !EvidenceReader.find(freshEvidence, EvidenceType.CONTAINER_STATUS, "order-api", now).isPresent()) {
            emit(seq, runId, incidentId, null, "fresh_precheck_passed", "failed", "missing evidence types");
            return failNoEffect(grant.incidentId(), "precheck-failed-" + runId, runId, "missing evidence types");
        }
        PrecheckResult precheck = checkAppDownPrecondition(freshEvidence, now);
        if (precheck == PrecheckResult.SELF_RECOVERED) {
            emit(seq, runId, incidentId, null, "fresh_precheck_passed", "failed", "self-recovered");
            return new RepairResult(grant.incidentId(), "self-recovered-" + runId, runId,
                AttemptState.CANCELLED_NO_EFFECT, EffectCertainty.NO_EFFECT_CONFIRMED,
                FailureClass.PRECONDITION_FAILED, "service self-recovered", clock.instant(), clock.instant(), null);
        }
        if (precheck == PrecheckResult.EVIDENCE_CONFLICT) {
            emit(seq, runId, incidentId, null, "fresh_precheck_passed", "failed", "conflicting evidence");
            return failNoEffect(grant.incidentId(), "precheck-failed-" + runId, runId, "conflicting evidence");
        }
        emit(seq, runId, incidentId, null, "fresh_precheck_passed", "ok", "APP_DOWN confirmed");

        // Snapshot + grant validation
        String currentSnapshot = SnapshotHasher.compute(freshEvidence, serviceId);
        emit(seq, runId, incidentId, null, "snapshot_computed", "ok",
            "hash=" + trunc(currentSnapshot));
        ActionDescriptor descriptor = action.toActionDescriptor(canonicalTarget, serviceId, currentSnapshot);
        ApprovalGrant.ValidationResult validation = grant.validate(descriptor, currentSnapshot, incidentId, canonicalTarget);
        if (!validation.valid()) {
            emit(seq, runId, incidentId, null, "approval_grant_validated", "failed", validation.reason());
            return failNoEffect(grant.incidentId(), "rejected-" + runId, runId, "Grant invalidated: " + validation.reason());
        }
        emit(seq, runId, incidentId, null, "approval_grant_validated", "ok", "grant valid");
        emit(seq, runId, incidentId, null, "snapshot_match", "ok", "TOCTOU snapshot matches");

        // P1-G: begin
        String logicalActionId = descriptor.contentDerivedActionId();
        AttemptTicket ticket;
        try { ticket = coordinator.begin(descriptor, logicalActionId, runId, true); }
        catch (Exception e) {
            emit(seq, runId, incidentId, null, "attempt_started", "failed", e.getMessage());
            return failNoEffect(grant.incidentId(), "blocked-" + runId, runId, "begin: " + e.getMessage());
        }
        String attemptId = ticket.attemptId();
        emit(seq, runId, incidentId, attemptId, "attempt_started", "ok", "attemptId=" + attemptId);

        // Precheck
        try { coordinator.completePrecheck(ticket, true, "fresh precheck; snapshot=" + trunc(currentSnapshot)); }
        catch (Exception e) {
            emit(seq, runId, incidentId, attemptId, "precheck_completed", "failed", e.getMessage());
            return new RepairResult(grant.incidentId(), attemptId, runId, AttemptState.FAILED_NO_EFFECT,
                EffectCertainty.NOT_DISPATCHED, FailureClass.PRECONDITION_FAILED,
                "Precheck: " + e.getMessage(), clock.instant(), clock.instant(), null);
        }
        emit(seq, runId, incidentId, attemptId, "precheck_completed", "ok", "passed");

        // Durable DISPATCH_INTENT
        try { coordinator.markDispatchIntent(ticket); }
        catch (Exception e) {
            emit(seq, runId, incidentId, attemptId, "dispatch_intent_persisted", "failed", e.getMessage());
            return new RepairResult(grant.incidentId(), attemptId, runId, AttemptState.FAILED_NO_EFFECT,
                EffectCertainty.NOT_DISPATCHED, FailureClass.PRECONDITION_FAILED,
                "Dispatch: " + e.getMessage(), clock.instant(), clock.instant(), null);
        }
        emit(seq, runId, incidentId, attemptId, "dispatch_intent_persisted", "ok", "durable before remote call");

        // Execute
        emit(seq, runId, incidentId, attemptId, "execution_started", "in_progress", "opsfix restart_service");
        Instant start = clock.instant();
        RepairResult result;
        try {
            result = fixSession.executeRestart(grant.incidentId(), runId);
            emit(seq, runId, incidentId, attemptId, "execution_reported",
                result.certainty() == EffectCertainty.EFFECT_CONFIRMED ? "ok" : "failed",
                "certainty=" + result.certainty());
        } catch (IOException e) {
            emit(seq, runId, incidentId, attemptId, "execution_reported", "unknown", "SSH: " + e.getMessage());
            safeReportOutcome(ticket, EffectCertainty.EFFECT_UNKNOWN, FailureClass.TIMEOUT_OUTCOME_UNKNOWN, e.getMessage());
            return new RepairResult(grant.incidentId(), attemptId, runId, AttemptState.OUTCOME_UNKNOWN,
                EffectCertainty.EFFECT_UNKNOWN, FailureClass.TIMEOUT_OUTCOME_UNKNOWN,
                "SSH failure", start, clock.instant(), null);
        }

        ActionAttempt reported = safeReportOutcome(ticket, result.certainty(), result.failureClass(), result.detail());
        if (reported == null) {
            return new RepairResult(grant.incidentId(), attemptId, runId, AttemptState.OUTCOME_UNKNOWN,
                EffectCertainty.EFFECT_UNKNOWN, FailureClass.EXECUTION_ERROR_OUTCOME_UNKNOWN,
                "outcome journal failed", start, clock.instant(), null);
        }
        AttemptState finalState = reported.state();
        if (result.certainty() == EffectCertainty.EFFECT_CONFIRMED) {
            try { coordinator.startVerification(ticket); finalState = AttemptState.VERIFYING; }
            catch (Exception e) { log.warn("[repair:{}] startVerification: {}", runId, e.getMessage()); }
            emit(seq, runId, incidentId, attemptId, "verification_started", "in_progress", "P1-G verification");
        }
        return new RepairResult(grant.incidentId(), attemptId, result.repairRunId(),
            finalState, result.certainty(), result.failureClass(), result.detail(),
            start, result.completedAt(), null);
    }

    public RepairResult completeVerification(RepairResult repairResult, boolean passed, String evidence) {
        AttemptTicket ticket;
        try { ticket = coordinator.ticketFor(repairResult.attemptId()); }
        catch (Exception e) { return repairResult; }
        ActionAttempt attempt;
        try { attempt = coordinator.completeVerification(ticket, VerificationMode.WORKFLOW, passed, evidence); }
        catch (Exception e) { return repairResult; }
        AttemptState newState = attempt.state();
        VerificationResult vr = repairResult.verification();
        if (vr == null) vr = new VerificationResult("verify-" + repairResult.repairRunId(),
            repairResult.attemptId(), passed, List.of(), null, passed, false);
        return new RepairResult(repairResult.incidentId(), repairResult.attemptId(),
            repairResult.repairRunId(), newState, repairResult.certainty(), repairResult.failureClass(),
            repairResult.detail(), repairResult.startedAt(), clock.instant(), vr);
    }

    public ActionAttemptCoordinator coordinator() { return coordinator; }

    // ── Precheck ──
    enum PrecheckResult { APP_DOWN, SELF_RECOVERED, EVIDENCE_CONFLICT }

    private PrecheckResult checkAppDownPrecondition(EvidenceBundle evidence, Instant now) {
        Optional<Evidence> svc = EvidenceReader.find(evidence, EvidenceType.SERVICE_STATUS, "order-api", now);
        Optional<Evidence> ctr = EvidenceReader.find(evidence, EvidenceType.CONTAINER_STATUS, "order-api", now);
        if (svc.isEmpty() && ctr.isEmpty()) return PrecheckResult.EVIDENCE_CONFLICT;
        if (svc.isPresent() && EvidenceReader.success(svc.get())) {
            if (EvidenceReader.isRunning(svc.get())) {
                Optional<Evidence> http = EvidenceReader.find(evidence, EvidenceType.HTTP_PROBE, null, now);
                if (http.isPresent() && EvidenceReader.dataHttpStatus(http.get()).orElse(-1) == 200)
                    return PrecheckResult.SELF_RECOVERED;
                return PrecheckResult.EVIDENCE_CONFLICT;
            }
            if (EvidenceReader.isStoppedOrExited(svc.get())) return PrecheckResult.APP_DOWN;
        }
        if (ctr.isPresent() && EvidenceReader.success(ctr.get())) {
            if (EvidenceReader.isStoppedOrExited(ctr.get())) return PrecheckResult.APP_DOWN;
            if (EvidenceReader.isRunning(ctr.get())) return PrecheckResult.EVIDENCE_CONFLICT;
        }
        return PrecheckResult.EVIDENCE_CONFLICT;
    }

    // ── Helpers ──
    private RepairResult failNoEffect(String iid, String aid, String rid, String detail) {
        return new RepairResult(iid, aid, rid, AttemptState.FAILED_NO_EFFECT,
            EffectCertainty.NOT_DISPATCHED, FailureClass.PRECONDITION_FAILED,
            detail, clock.instant(), clock.instant(), null);
    }

    private ActionAttempt safeReportOutcome(AttemptTicket ticket, EffectCertainty c, FailureClass fc, String reason) {
        try { return coordinator.reportOutcome(ticket, c, fc, reason); }
        catch (Exception e) { log.error("[repair] outcome failed: {}", e.getMessage()); return null; }
    }

    private static String trunc(String s) { return s != null && s.length() > 40 ? s.substring(0, 37) + "..." : s; }

    private void emit(AtomicInteger seq, String runId, String incidentId, String attemptId,
                       String stage, String status, String detail) {
        observer.onEvent(new RepairLifecycleObserver.RepairLifecycleEvent(
            seq.incrementAndGet(), clock.instant(), runId, incidentId, attemptId, stage, status, detail));
    }
}
