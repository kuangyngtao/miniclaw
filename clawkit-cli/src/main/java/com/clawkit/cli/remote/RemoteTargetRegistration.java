package com.clawkit.cli.remote;

import java.util.Objects;

/**
 * Schema v2 target registration — the domain object stored in
 * {@link RemoteTargetStore}.
 *
 * <p>Contains only logical identifiers: targetId, connection reference,
 * and profile manifest ID. Never stores key paths, host IPs,
 * known_hosts content, agent sockets, tool hashes, or contract hashes.
 *
 * <p>Design: PRODUCT-1 §5.1.
 */
public record RemoteTargetRegistration(
    int schemaVersion,
    String targetId,
    RemoteConnectionReference connection,
    String profileManifestId
) {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public RemoteTargetRegistration {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(profileManifestId, "profileManifestId");
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                "unsupported schemaVersion: " + schemaVersion
                + " (expected " + CURRENT_SCHEMA_VERSION + ")");
        }
        com.clawkit.tools.remote.RemoteSshSafetyPolicy.validateTargetId(targetId);
        if (profileManifestId.isBlank()) {
            throw new IllegalArgumentException("profileManifestId must not be blank");
        }
        if (!RemoteProfileCatalog.isKnown(profileManifestId)) {
            throw new IllegalArgumentException(
                "unknown profile manifest: " + profileManifestId);
        }
    }
}
