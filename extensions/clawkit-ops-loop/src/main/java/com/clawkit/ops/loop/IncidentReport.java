package com.clawkit.ops.loop;

import java.time.Instant;
import java.util.List;

public record IncidentReport(
    String incidentId,
    String runId,
    IncidentState state,
    Instant discoveredAt,
    Instant completedAt,
    EvidenceBundle evidenceBundle,
    Diagnosis diagnosis,
    List<IncidentTransition> transitions
) {
    public IncidentReport {
        transitions = List.copyOf(transitions);
    }
}
