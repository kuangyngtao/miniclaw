package com.clawkit.tools.remote;

import java.util.Objects;

/**
 * Secret-free descriptor for a remote target.
 *
 * <p>Safe to include in events, reports, tool descriptions, and model context.
 * Must NOT contain host, user, key path, or any connection details.
 *
 * <p>This is the canonical target identity used for attestation —
 * every field must match the remote server's initialize + tools/list response
 * for a connection to reach READY.
 *
 * <p>Design: REMOTE-0 §6.1.
 */
public record RemoteTargetDescriptor(
    String targetId,
    String expectedServerName,
    String expectedProtocolVersion,
    String expectedProbeVersion,
    String expectedCapabilityProfile,
    String expectedToolSetHash,
    String expectedToolContractHash
) {
    public RemoteTargetDescriptor {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(expectedServerName, "expectedServerName");
        Objects.requireNonNull(expectedProtocolVersion, "expectedProtocolVersion");
        Objects.requireNonNull(expectedProbeVersion, "expectedProbeVersion");
        Objects.requireNonNull(expectedCapabilityProfile, "expectedCapabilityProfile");
        Objects.requireNonNull(expectedToolSetHash, "expectedToolSetHash");
        Objects.requireNonNull(expectedToolContractHash, "expectedToolContractHash");
        if (targetId.isBlank()) throw new IllegalArgumentException("targetId must not be blank");
        if (expectedServerName.isBlank()) throw new IllegalArgumentException("expectedServerName must not be blank");
        if (expectedProtocolVersion.isBlank()) throw new IllegalArgumentException("expectedProtocolVersion must not be blank");
        if (expectedProbeVersion.isBlank()) throw new IllegalArgumentException("expectedProbeVersion must not be blank");
        if (expectedCapabilityProfile.isBlank()) throw new IllegalArgumentException("expectedCapabilityProfile must not be blank");
        if (expectedToolSetHash.isBlank()) throw new IllegalArgumentException("expectedToolSetHash must not be blank");
        if (expectedToolContractHash.isBlank()) throw new IllegalArgumentException("expectedToolContractHash must not be blank");
        if (!targetId.matches("[a-z0-9][a-z0-9_-]{0,62}")) {
            throw new IllegalArgumentException(
                "targetId must match [a-z0-9][a-z0-9_-]{0,62}: " + targetId);
        }
    }

    /** Create a descriptor with the expected tool contract hash computed from tool definitions. */
    public static RemoteTargetDescriptor withComputedHash(
        String targetId,
        String expectedServerName,
        String expectedProtocolVersion,
        String expectedProbeVersion,
        String expectedCapabilityProfile,
        String expectedToolSetHash,
        java.util.List<com.clawkit.tools.mcp.McpToolDef> expectedTools
    ) {
        String contractHash = ToolContractHash.computeFromMcpTools(expectedTools);
        return new RemoteTargetDescriptor(
            targetId, expectedServerName, expectedProtocolVersion,
            expectedProbeVersion, expectedCapabilityProfile,
            expectedToolSetHash, contractHash);
    }
}
