package com.clawkit.ops.loop.repair;

import com.clawkit.ops.loop.Diagnosis;

import java.util.List;
import java.util.Objects;

/**
 * Model-generated repair suggestion — NOT executable.
 *
 * <p>The model outputs a suggestion based on diagnosis. The policy gate
 * evaluates applicability. A human approves. Only then does it become
 * an actionable {@link com.clawkit.tools.action.ActionDescriptor}.
 *
 * <p>Model confidence does NOT trigger execution. The gate is deterministic.
 */
public record RepairSuggestion(
    String incidentId,
    String actionCode,
    String serviceId,
    String justification,
    double modelConfidence,
    List<String> evidenceRefs
) {
    public RepairSuggestion {
        Objects.requireNonNull(incidentId, "incidentId required");
        Objects.requireNonNull(actionCode, "actionCode required");
        Objects.requireNonNull(serviceId, "serviceId required");
        if (modelConfidence < 0 || modelConfidence > 1) {
            throw new IllegalArgumentException("modelConfidence must be between 0 and 1");
        }
        justification = justification != null ? justification : "";
        evidenceRefs = evidenceRefs != null ? List.copyOf(evidenceRefs) : List.of();
    }

    /**
     * Check if this suggestion is applicable to the given diagnosis.
     * Only APP_DOWN diagnoses are eligible for restart_service in MVP-3.
     */
    public boolean isApplicable(Diagnosis diagnosis) {
        if (diagnosis == null) return false;
        // The only allowed trigger: the diagnostic signals indicate the app is down
        String rootCause = diagnosis.rootCauseCode();
        return "APP_DOWN".equals(rootCause);
    }
}
