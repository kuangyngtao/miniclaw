package com.clawkit.cli.remote;

import java.util.Objects;

/**
 * OpenSSH Host alias connection reference.
 *
 * <p>Stores only the alias and the fixed restricted remote user.
 * Does NOT store key paths, host IPs, ports, or known_hosts content.
 * OpenSSH resolves everything at connection time from the user's config.
 *
 * <p>Design: PRODUCT-1 §5.1.
 */
public record OpenSshAliasReference(
    String alias,
    String remoteUser,
    java.nio.file.Path sshConfigFile
) implements RemoteConnectionReference {

    public OpenSshAliasReference(String alias, String remoteUser) {
        this(alias, remoteUser, null);
    }

    public OpenSshAliasReference {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(remoteUser, "remoteUser");
        com.clawkit.tools.remote.RemoteSshSafetyPolicy.validateAlias(alias);
        if (remoteUser.isBlank()) throw new IllegalArgumentException("remoteUser must not be blank");
    }

    @Override
    public String type() {
        return "openssh-alias";
    }
}
