package com.clawkit.cli.remote;

import java.util.Objects;

/**
 * Legacy v1 explicit endpoint reference (host/port/user/key/known_hosts).
 *
 * <p>This is the compatibility path for existing v1 YAML configs.
 * New registrations should prefer {@link OpenSshAliasReference}.
 *
 * <p>Design: PRODUCT-1 §5.1.
 */
public record LegacyExplicitEndpointReference(
    String host,
    int port,
    String user,
    String identityFileRef,
    String knownHostsFile
) implements RemoteConnectionReference {

    public LegacyExplicitEndpointReference {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(identityFileRef, "identityFileRef");
        Objects.requireNonNull(knownHostsFile, "knownHostsFile");
        if (host.isBlank()) throw new IllegalArgumentException("host must not be blank");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range");
    }

    @Override
    public String type() {
        return "explicit-endpoint";
    }
}
