package com.clawkit.tools.remote;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Full attestation snapshot produced after a successful MCP handshake.
 *
 * <p>Only created when ALL checks pass — protocol, server identity,
 * probe version, capability profile, tool-set hash, and tool-contract hash.
 * Both the server-advertised and locally-computed hashes are recorded
 * for diagnostics.
 *
 * <p>Safe to include in events and status displays.
 *
 * <p>Design: REMOTE-0 §6.4.
 */
public record RemoteAttestationSnapshot(
    String targetId,
    String serverName,
    String protocolVersion,
    String probeVersion,
    String capabilityProfile,
    String advertisedToolSetHash,
    String computedToolSetHash,
    String computedToolContractHash,
    List<String> toolNames,
    long connectLatencyMs,
    long attestationLatencyMs,
    Instant attestedAt
) {
    public RemoteAttestationSnapshot {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(probeVersion, "probeVersion");
        Objects.requireNonNull(capabilityProfile, "capabilityProfile");
        Objects.requireNonNull(advertisedToolSetHash, "advertisedToolSetHash");
        Objects.requireNonNull(computedToolSetHash, "computedToolSetHash");
        Objects.requireNonNull(computedToolContractHash, "computedToolContractHash");
        Objects.requireNonNull(toolNames, "toolNames");
        Objects.requireNonNull(attestedAt, "attestedAt");
        if (targetId.isBlank()) throw new IllegalArgumentException("targetId must not be blank");
        if (toolNames.isEmpty()) throw new IllegalArgumentException("toolNames must not be empty");
        if (connectLatencyMs < 0) throw new IllegalArgumentException("connectLatencyMs must be >= 0");
        if (attestationLatencyMs < 0) throw new IllegalArgumentException("attestationLatencyMs must be >= 0");
    }

    /** Total latency from connect start to READY. */
    public long totalLatencyMs() {
        return connectLatencyMs + attestationLatencyMs;
    }
}
