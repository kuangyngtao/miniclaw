package com.clawkit.tools.remote;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Lightweight connection-status snapshot safe for CLI display and events.
 *
 * <p>Never exposes the {@link RemoteEndpointConfig}. The attestation field
 * is present only when the connection reached READY (or DEGRADED).
 *
 * <p>Design: REMOTE-0 §6.5.
 */
public record RemoteConnectionSnapshot(
    String targetId,
    RemoteConnectionState state,
    long generation,
    RemoteAttestationSnapshot attestation,
    List<String> mountedTools,
    RemoteError error,
    Instant changedAt
) {
    public RemoteConnectionSnapshot {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(changedAt, "changedAt");
        if (targetId.isBlank()) throw new IllegalArgumentException("targetId must not be blank");
        if (generation < 0) throw new IllegalArgumentException("generation must be >= 0");
    }

    /** Convenience: create a DISCONNECTED snapshot. */
    public static RemoteConnectionSnapshot disconnected(String targetId) {
        return new RemoteConnectionSnapshot(
            targetId, RemoteConnectionState.DISCONNECTED, 0,
            null, List.of(), null, Instant.now());
    }
}
