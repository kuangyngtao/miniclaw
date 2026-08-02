package com.clawkit.ops.loop;

import java.util.Objects;

/**
 * Secret-free descriptor for a remote OPS target.
 *
 * <p>Safe to include in events, reports, and model context.
 * Must NOT contain host, user, key path, or connection details.
 *
 * <p>Design doc §7.1.
 */
public record RemoteTargetDescriptor(
    String targetId,
    String capabilityProfile,
    String expectedProbeVersion,
    String expectedToolSetHash,
    String expectedToolContractHash
) {
    public RemoteTargetDescriptor {
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(capabilityProfile, "capabilityProfile must not be null");
        Objects.requireNonNull(expectedProbeVersion, "expectedProbeVersion must not be null");
        Objects.requireNonNull(expectedToolSetHash, "expectedToolSetHash must not be null");
        Objects.requireNonNull(expectedToolContractHash, "expectedToolContractHash must not be null");
        if (targetId.isBlank()) throw new IllegalArgumentException("targetId must not be blank");
        if (capabilityProfile.isBlank()) throw new IllegalArgumentException("capabilityProfile must not be blank");
        if (expectedProbeVersion.isBlank()) throw new IllegalArgumentException("expectedProbeVersion must not be blank");
        if (expectedToolSetHash.isBlank()) throw new IllegalArgumentException("expectedToolSetHash must not be blank");
        if (expectedToolContractHash.isBlank()) throw new IllegalArgumentException("expectedToolContractHash must not be blank");
    }

    /** Backward-compat constructor without contract hash (computes from profile). */
    public RemoteTargetDescriptor(String targetId, String capabilityProfile,
                                   String expectedProbeVersion, String expectedToolSetHash) {
        this(targetId, capabilityProfile, expectedProbeVersion, expectedToolSetHash,
            com.clawkit.ops.mcp.OpsMcpServer.computeExpectedToolContractHash(
                com.clawkit.ops.mcp.OpsCapabilityProfile.fromEnvironment(capabilityProfile)));
    }
}
