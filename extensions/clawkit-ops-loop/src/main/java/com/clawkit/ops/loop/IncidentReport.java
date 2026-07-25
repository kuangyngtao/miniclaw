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
    List<IncidentTransition> transitions,
    String schemaVersion,
    String capabilityProfile,
    String model,
    String promptVersion,
    String timelineReference
) {
    public IncidentReport {
        transitions = List.copyOf(transitions);
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "1" : schemaVersion;
        capabilityProfile = capabilityProfile == null ? "APP_DOWN_V1" : capabilityProfile;
        model = model == null ? "unknown" : model;
        promptVersion = promptVersion == null ? "unknown" : promptVersion;
    }

    public IncidentReport(
        String incidentId, String runId, IncidentState state, Instant discoveredAt,
        Instant completedAt, EvidenceBundle evidenceBundle, Diagnosis diagnosis,
        List<IncidentTransition> transitions
    ) {
        this(incidentId, runId, state, discoveredAt, completedAt, evidenceBundle,
            diagnosis, transitions, "1", "APP_DOWN_V1", "unknown", "unknown", null);
    }
}
