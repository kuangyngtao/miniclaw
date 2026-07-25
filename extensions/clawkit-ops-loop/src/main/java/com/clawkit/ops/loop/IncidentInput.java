package com.clawkit.ops.loop;

import java.time.Instant;

/** The complete model-visible incident contract. It deliberately contains no case label. */
public record IncidentInput(
    String incidentId,
    Instant detectedAt,
    String symptom,
    String capabilityProfile,
    String promptVersion
) {
    public IncidentInput {
        if (incidentId == null || incidentId.isBlank()) throw new IllegalArgumentException("incidentId required");
        if (detectedAt == null) throw new IllegalArgumentException("detectedAt required");
        if (!"POSTGRES_DIAGNOSIS_V1".equals(capabilityProfile)) {
            throw new IllegalArgumentException("POSTGRES_DIAGNOSIS_V1 required");
        }
    }
}
