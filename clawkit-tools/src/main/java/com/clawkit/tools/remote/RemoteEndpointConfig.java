package com.clawkit.tools.remote;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Local-only SSH endpoint configuration.
 *
 * <p>Must NEVER be serialized into events, reports, or model context.
 * Contains the real host, user, resolved key path, and connection parameters.
 *
 * <p>This is the generic REMOTE-0 endpoint type. Implements
 * {@link RemoteSshConnectionSpec} for unified consumption by
 * {@link RemoteMcpSession}.
 *
 * <p>Legacy explicit mode always uses {@code -F none} to completely
 * isolate from user and system SSH config — a hostname must not trigger
 * {@code ProxyCommand} or {@code Match exec} from the user's config.
 *
 * <p>Design: REMOTE-0 §6.2, PRODUCT-1 §6.1.
 */
public record RemoteEndpointConfig(
    String host,
    int port,
    String user,
    CredentialRef identityFileRef,
    Path knownHostsFile,
    Duration connectTimeout,
    Duration requestTimeout,
    int maxOutputBytes
) implements RemoteSshConnectionSpec {

    public RemoteEndpointConfig {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(identityFileRef, "identityFileRef");
        Objects.requireNonNull(knownHostsFile, "knownHostsFile");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (host.isBlank()) throw new IllegalArgumentException("host must not be blank");
        if (user.isBlank()) throw new IllegalArgumentException("user must not be blank");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
        if (connectTimeout.isNegative() || connectTimeout.isZero())
            throw new IllegalArgumentException("connectTimeout must be positive");
        if (requestTimeout.isNegative() || requestTimeout.isZero())
            throw new IllegalArgumentException("requestTimeout must be positive");
        if (maxOutputBytes < 256) throw new IllegalArgumentException("maxOutputBytes must be >= 256");
    }

    /**
     * Build the SSH command arguments for legacy explicit endpoint mode.
     *
     * <p>Uses {@code -F none} to completely isolate from user/system SSH config.
     * Safety parameters come first (from {@link RemoteSshSafetyPolicy}),
     * then explicit host/key/port/user, then the destination.
     *
     * @return the ssh command argument list (excluding the ssh executable itself)
     */
    @Override
    public List<String> sshArgs() {
        Path resolvedKey = identityFileRef.resolve();
        List<String> args = new ArrayList<>();

        // Safety prefix (first-wins for SSH options)
        args.addAll(RemoteSshSafetyPolicy.safetyArgs((int) connectTimeout.toSeconds()));

        // -F none: completely isolate from SSH config files
        args.add("-F"); args.add("none");

        // Explicit identity
        args.add("-i"); args.add(resolvedKey.toAbsolutePath().toString());

        // Explicit known_hosts
        args.add("-o"); args.add("UserKnownHostsFile=" + knownHostsFile.toAbsolutePath());
        args.add("-o"); args.add("IdentitiesOnly=yes");

        // Port + destination
        args.add("-p"); args.add(String.valueOf(port));
        args.add(user + "@" + host);

        return List.copyOf(args);
    }

    /** Sanitized target reference for error messages (no key path). */
    @Override
    public String safeRef() {
        return user + "@" + host + ":" + port;
    }
}
