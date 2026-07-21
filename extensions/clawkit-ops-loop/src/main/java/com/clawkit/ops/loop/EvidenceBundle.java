package com.clawkit.ops.loop;

import java.time.Instant;
import java.util.List;

public record EvidenceBundle(
    String incidentId,
    String runId,
    Instant createdAt,
    List<Evidence> evidence
) {
    public EvidenceBundle {
        if (incidentId == null || incidentId.isBlank()
            || runId == null || runId.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("bundle identity fields must not be blank");
        }
        evidence = List.copyOf(evidence);
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("evidence bundle must not be empty");
        }
        if (evidence.stream().anyMatch(e -> !incidentId.equals(e.incidentId()))) {
            throw new IllegalArgumentException("all evidence must belong to the incident");
        }
    }
}
