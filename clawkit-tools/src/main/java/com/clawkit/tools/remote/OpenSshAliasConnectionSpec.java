package com.clawkit.tools.remote;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * OpenSSH alias-based connection specification.
 *
 * <p>Preserves the SSH Host alias — does NOT expand to host/key/port.
 * This lets OpenSSH resolve the actual config including Include,
 * ProxyJump, SSH Agent, certificates, and multiple IdentityFile entries.
 *
 * <p>The final command is:
 * <pre>{@code
 * ssh <safety-args> [-F <configFile>] -l <remoteUser> <alias>
 * }</pre>
 *
 * <p>When {@code configFile} is null (production default), OpenSSH uses
 * the default {@code ~/.ssh/config}. Specify for testing with temp configs.
 *
 * <p>Alias mode deliberately does NOT use {@code -i}, {@code IdentitiesOnly=yes},
 * or {@code -F none} — those would break Agent, certificates, and
 * multi-IdentityFile configurations.
 *
 * <p>Design: PRODUCT-1 §5.2, §6.1.
 */
public record OpenSshAliasConnectionSpec(
    String alias,
    String remoteUser,
    Path configFile,
    Duration connectTimeout,
    Duration requestTimeout,
    int maxOutputBytes
) implements RemoteSshConnectionSpec {

    /** Production constructor — uses default ~/.ssh/config. */
    public OpenSshAliasConnectionSpec(String alias, String remoteUser,
                                       Duration connectTimeout,
                                       Duration requestTimeout,
                                       int maxOutputBytes) {
        this(alias, remoteUser, null, connectTimeout, requestTimeout, maxOutputBytes);
    }

    public OpenSshAliasConnectionSpec {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(remoteUser, "remoteUser");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        RemoteSshSafetyPolicy.validateAlias(alias);
        if (remoteUser.isBlank()) throw new IllegalArgumentException("remoteUser must not be blank");
        if (connectTimeout.isNegative() || connectTimeout.isZero())
            throw new IllegalArgumentException("connectTimeout must be positive");
        if (requestTimeout.isNegative() || requestTimeout.isZero())
            throw new IllegalArgumentException("requestTimeout must be positive");
        if (maxOutputBytes < 256) throw new IllegalArgumentException("maxOutputBytes must be >= 256");
    }

    @Override
    public List<String> sshArgs() {
        List<String> args = new ArrayList<>();

        // Safety prefix (first-wins for SSH options)
        args.addAll(RemoteSshSafetyPolicy.safetyArgs((int) connectTimeout.toSeconds()));

        // Custom config file (testing / non-default config locations)
        if (configFile != null) {
            args.add("-F"); args.add(configFile.toAbsolutePath().toString());
        }

        // Explicit remote user
        args.add("-l"); args.add(remoteUser);

        // The alias — OpenSSH resolves everything else
        args.add(alias);

        return List.copyOf(args);
    }

    /** Sanitized reference — alias only, no host key or IP. */
    @Override
    public String safeRef() {
        return "alias:" + alias + " (user=" + remoteUser + ")";
    }
}
