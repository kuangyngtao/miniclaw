package com.clawkit.ops.loop.repair;

import com.clawkit.ops.loop.Diagnosis;

/**
 * Deterministic policy gate that evaluates whether a repair suggestion
 * is eligible for approval.
 *
 * <p>Rules (MVP-3):
 * <ul>
 *   <li>Only APP_DOWN root cause → restart_service</li>
 *   <li>Only order-api as target service</li>
 *   <li>DB_LOCK_WAIT → explicitly denied (restart won't help)</li>
 *   <li>Unknown action or service → fail closed</li>
 *   <li>Model confidence does not trigger execution</li>
 * </ul>
 *
 * <p>This gate runs BEFORE the approval prompt. It is deterministic
 * and cannot be overridden by model output.
 */
public final class RepairPolicyGate {

    private RepairPolicyGate() {}

    /**
     * Evaluate whether a repair suggestion is eligible for the given diagnosis.
     *
     * @param diagnosis  the reconciled diagnosis (deterministic signals + model interpretation)
     * @param suggestion the model-generated repair suggestion (not executable)
     * @return ALLOWED or DENIED with a reason
     */
    public static GateDecision evaluate(Diagnosis diagnosis, RepairSuggestion suggestion) {
        // Null guards
        if (diagnosis == null) {
            return GateDecision.denied("diagnosis is null");
        }
        if (suggestion == null) {
            return GateDecision.denied("repair suggestion is null");
        }

        String rootCause = diagnosis.rootCauseCode();
        String actionCode = suggestion.actionCode();
        String serviceId = suggestion.serviceId();

        // Rule 1: Only restart_service is allowed
        if (!"restart_service".equals(actionCode)) {
            return GateDecision.denied(
                "unknown action code '" + actionCode + "': only restart_service is allowed");
        }

        // Rule 2: Only order-api as target service
        if (!"order-api".equals(serviceId)) {
            return GateDecision.denied(
                "serviceId '" + serviceId + "' is not in allowlist: only order-api is allowed");
        }

        // Rule 3: Only APP_DOWN root cause is eligible
        // DB_LOCK_WAIT, CPU_PRESSURE, CONNECTION_EXHAUSTION, INCONCLUSIVE → DENIED
        if (!"APP_DOWN".equals(rootCause)) {
            return GateDecision.denied(
                "root cause '" + rootCause + "' is not eligible for restart_service: "
                + "only APP_DOWN is eligible. " + specificDenialReason(rootCause));
        }

        // Rule 4: Diagnosis must be conclusive enough
        if (diagnosis.diagnosisStatus() == Diagnosis.DiagnosisStatus.INCONCLUSIVE) {
            return GateDecision.denied(
                "diagnosis is INCONCLUSIVE: cannot authorize repair without a confirmed root cause");
        }

        return GateDecision.ALLOWED;
    }

    private static String specificDenialReason(String rootCause) {
        return switch (rootCause) {
            case "DB_LOCK_WAIT" -> "Database lock wait cannot be resolved by restarting the application";
            case "CPU_PRESSURE" -> "CPU pressure cannot be resolved by restarting the application";
            case "CONNECTION_EXHAUSTION" -> "Connection exhaustion cannot be resolved by restarting the application";
            case "INCONCLUSIVE" -> "Inconclusive diagnosis requires more evidence, not a repair attempt";
            default -> "Unknown or unsupported root cause";
        };
    }
}
