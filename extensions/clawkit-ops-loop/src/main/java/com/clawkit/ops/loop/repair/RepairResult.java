package com.clawkit.ops.loop.repair;

import com.clawkit.reliability.attempt.AttemptState;
import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.action.FailureClass;

import java.time.Instant;

/**
 * Aggregated result of a repair execution.
 *
 * <p>Contains the P1-G attempt state, the effect certainty, timing,
 * and the verification result if verification was completed.
 */
public record RepairResult(
    String incidentId,
    String attemptId,
    String repairRunId,
    AttemptState attemptState,
    EffectCertainty certainty,
    FailureClass failureClass,
    String detail,
    Instant startedAt,
    Instant completedAt,
    VerificationResult verification
) {
    public RepairResult {
        if (incidentId == null || incidentId.isBlank()) throw new IllegalArgumentException("incidentId required");
        if (attemptId == null || attemptId.isBlank()) throw new IllegalArgumentException("attemptId required");
        if (repairRunId == null || repairRunId.isBlank()) throw new IllegalArgumentException("repairRunId required");
        if (attemptState == null) throw new IllegalArgumentException("attemptState required");
        if (startedAt == null) startedAt = Instant.now();
        if (completedAt == null) completedAt = startedAt;
        if (detail == null) detail = "";
    }

    public boolean isSuccess() {
        return attemptState == AttemptState.VERIFIED_SUCCESS;
    }

    public boolean isFailed() {
        return attemptState == AttemptState.FAILED_NO_EFFECT
            || attemptState == AttemptState.CANCELLED_NO_EFFECT;
    }

    public boolean isUnknown() {
        return attemptState == AttemptState.OUTCOME_UNKNOWN;
    }

    public boolean needsVerification() {
        return attemptState == AttemptState.VERIFICATION_PENDING
            && verification == null;
    }

    public RepairResult withVerification(VerificationResult vr) {
        return new RepairResult(incidentId, attemptId, repairRunId,
            attemptState, certainty, failureClass, detail,
            startedAt, completedAt, vr);
    }
}
