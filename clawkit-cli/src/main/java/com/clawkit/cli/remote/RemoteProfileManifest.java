package com.clawkit.cli.remote;

import java.util.Objects;

/**
 * Versioned, built-in profile manifest.
 *
 * <p>Contains the expected server identity, protocol, tool contracts
 * and access mode. Used to resolve a registration's {@code profileManifestId}
 * into the runtime {@link com.clawkit.tools.remote.RemoteTargetDescriptor}
 * and {@link com.clawkit.tools.remote.RemoteSshConnectionSpec}.
 *
 * <p>FIX profiles (write-capable) must not appear in the built-in catalog.
 * Unknown manifest IDs fail closed.
 *
 * <p>Design: PRODUCT-1 §5.3.
 */
public record RemoteProfileManifest(
    int schemaVersion,
    String manifestId,
    String displayName,
    String serverName,
    String protocolVersion,
    String probeVersion,
    String capabilityProfile,
    String expectedToolSetHash,
    String expectedToolContractHash,
    RemoteAccessMode accessMode,
    String requiredRemoteUser
) {
    public RemoteProfileManifest {
        Objects.requireNonNull(manifestId, "manifestId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(probeVersion, "probeVersion");
        Objects.requireNonNull(capabilityProfile, "capabilityProfile");
        Objects.requireNonNull(expectedToolSetHash, "expectedToolSetHash");
        Objects.requireNonNull(expectedToolContractHash, "expectedToolContractHash");
        Objects.requireNonNull(accessMode, "accessMode");
        Objects.requireNonNull(requiredRemoteUser, "requiredRemoteUser");
        if (accessMode == RemoteAccessMode.READ_WRITE) {
            throw new IllegalArgumentException(
                "FIX/write profiles must not appear in built-in catalog: " + manifestId);
        }
    }
}
