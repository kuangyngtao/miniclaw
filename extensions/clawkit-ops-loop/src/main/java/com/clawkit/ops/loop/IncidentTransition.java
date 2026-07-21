package com.clawkit.ops.loop;

import java.time.Instant;
import java.util.List;

public record IncidentTransition(
    IncidentState from,
    IncidentState to,
    String reason,
    Instant occurredAt,
    String runId,
    List<String> evidenceReferences
) {
    public IncidentTransition {
        if (from == null || to == null || reason == null || reason.isBlank()
            || occurredAt == null || runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("transition fields must not be blank");
        }
        evidenceReferences = List.copyOf(evidenceReferences);
    }
}
