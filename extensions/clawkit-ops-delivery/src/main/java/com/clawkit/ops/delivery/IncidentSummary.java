package com.clawkit.ops.delivery;

import java.time.Instant;

/**
 * Lightweight incident summary for the {@code /ops recent} list.
 *
 * <p>OPS-PRODUCT-LOOP-1 §5.5.
 */
public record IncidentSummary(
    String incidentId,
    String targetId,
    String serviceId,
    UserIncidentStatus status,
    String briefSummary,
    Instant createdAt,
    Instant updatedAt
) {
    public IncidentSummary {
        if (incidentId == null || incidentId.isBlank())
            throw new IllegalArgumentException("incidentId required");
        if (status == null) throw new IllegalArgumentException("status required");
        briefSummary = briefSummary != null ? briefSummary : "";
    }
}
