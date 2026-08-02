package com.clawkit.ops.loop.repair;

import java.time.Instant;

/**
 * Minimal observer for repair lifecycle events. Default no-op.
 * Production wiring in RemoteIncidentRepairMain writes to lifecycle-events.ndjson.
 */
public interface RepairLifecycleObserver {
    void onEvent(RepairLifecycleEvent event);

    RepairLifecycleObserver NOOP = event -> {};

    record RepairLifecycleEvent(
        int sequence,
        Instant timestamp,
        String runId,
        String incidentId,
        String attemptId,
        String stage,
        String status,
        String detail
    ) {}
}
