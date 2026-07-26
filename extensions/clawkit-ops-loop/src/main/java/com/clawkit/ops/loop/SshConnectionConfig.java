package com.clawkit.ops.loop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Local-only SSH connection configuration.
 *
 * <p>Must NEVER be serialized into events, reports, or model context.
 * Contains the real host, user, key path, and connection parameters.
 *
 * <p>Design doc §7.1–§7.2.
 */
public record SshConnectionConfig(
    String host,
    int port,
    String user,
    Path identityFile,
    Path knownHostsFile,
    Duration connectTimeout,
    Duration requestTimeout,
    int maxOutputBytes
) {
    public SshConnectionConfig {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(identityFile, "identityFile");
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
        if (!Files.isRegularFile(identityFile))
            throw new IllegalArgumentException("identity file not found: " + identityFile);
    }

    /**
     * Build the SSH command arguments per design doc §7.2.
     *
     * <p>Uses {@code ssh -T} with BatchMode, no password auth, strict host key
     * checking, explicit known hosts, keep-alive probes, and no forwarding.
     * No ControlMaster. No remote command (server forced-command handles it).
     *
     * @return the ssh command argument list (excluding the ssh executable itself)
     */
    public List<String> sshArgs() {
        List<String> args = new ArrayList<>();
        args.add("-T");
        args.add("-o"); args.add("BatchMode=yes");
        args.add("-o"); args.add("PasswordAuthentication=no");
        args.add("-o"); args.add("KbdInteractiveAuthentication=no");
        args.add("-o"); args.add("IdentitiesOnly=yes");
        args.add("-o"); args.add("StrictHostKeyChecking=yes");
        args.add("-o"); args.add("UserKnownHostsFile=" + knownHostsFile.toAbsolutePath());
        args.add("-o"); args.add("ConnectTimeout=" + Math.max(1, (int) connectTimeout.toSeconds()));
        args.add("-o"); args.add("ServerAliveInterval=10");
        args.add("-o"); args.add("ServerAliveCountMax=2");
        args.add("-o"); args.add("ClearAllForwardings=yes");
        args.add("-i"); args.add(identityFile.toAbsolutePath().toString());
        args.add("-p"); args.add(String.valueOf(port));
        args.add(user + "@" + host);
        return List.copyOf(args);
    }

    /** Sanitized target reference for error messages (no key path). */
    public String safeRef() {
        return user + "@" + host + ":" + port;
    }
}
