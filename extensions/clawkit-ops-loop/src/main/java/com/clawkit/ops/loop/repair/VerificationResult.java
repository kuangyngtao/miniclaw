package com.clawkit.ops.loop.repair;

import com.clawkit.ops.loop.EvidenceBundle;

import java.util.List;
import java.util.Objects;

/**
 * Result of an independent post-repair verification.
 *
 * <p>The verification is performed by a NEW opsro session that re-collects
 * fresh evidence. It does NOT trust the repair executor's claim of success.
 */
public record VerificationResult(
    String verificationRunId,
    String repairAttemptId,
    boolean passed,
    List<VerificationCheck> checks,
    EvidenceBundle collectedEvidence,
    boolean businessInvariantsPassed,
    boolean newErrorsDetected
) {
    public VerificationResult {
        Objects.requireNonNull(verificationRunId, "verificationRunId required");
        Objects.requireNonNull(repairAttemptId, "repairAttemptId required");
        checks = checks != null ? List.copyOf(checks) : List.of();
    }

    public List<String> failureReasons() {
        return checks.stream()
            .filter(c -> !c.passed())
            .map(c -> c.name() + ": " + c.detail())
            .toList();
    }

    public record VerificationCheck(String name, boolean passed, String detail) {
        public VerificationCheck {
            Objects.requireNonNull(name, "name required");
            if (detail == null) detail = passed ? "ok" : "failed";
        }

        public static VerificationCheck pass(String name) {
            return new VerificationCheck(name, true, "ok");
        }

        public static VerificationCheck fail(String name, String detail) {
            return new VerificationCheck(name, false, detail);
        }
    }
}
