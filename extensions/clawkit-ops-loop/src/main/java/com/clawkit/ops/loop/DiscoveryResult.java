package com.clawkit.ops.loop;

import java.time.Instant;

/**
 * Result of a single remote discovery run.
 *
 * <p>PR-M3 §5. Carries the frozen evidence bundle, overall status,
 * and completeness counts.
 */
public record DiscoveryResult(
    String incidentId,
    String runId,
    String profileName,
    EvidenceBundle bundle,
    DiscoveryStatus status,
    int requiredSuccess,
    int requiredTotal,
    Instant completedAt
) {
    public DiscoveryResult {
        if (incidentId == null || incidentId.isBlank()) throw new IllegalArgumentException("incidentId");
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId");
        if (profileName == null) throw new IllegalArgumentException("profileName");
        if (bundle == null) throw new IllegalArgumentException("bundle");
        if (status == null) throw new IllegalArgumentException("status");
        if (completedAt == null) throw new IllegalArgumentException("completedAt");
    }

    public boolean isComplete() { return status == DiscoveryStatus.COMPLETE; }
}
