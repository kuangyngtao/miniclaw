package com.clawkit.ops.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SSH connection parameters for a remote ops target.
 *
 * <p>Passphrases are never stored here — use {@code ssh-agent + ssh-add} to
 * unlock keys before starting the MCP server. The executor will fail with a
 * clear diagnostic if key authentication is not available.
 *
 * <p>Password authentication is supported only as a transitional fallback via
 * {@code sshpass}. It must be explicitly opted into and is logged with a
 * warning.
 */
public record SshTargetConfig(
    String host,
    int port,
    String user,
    AuthMethod authMethod,
    Path identityFile,
    String password,
    Duration connectTimeout,
    int maxConcurrentSessions,
    boolean strictHostKeyChecking,
    Path knownHostsFile
) {
    public enum AuthMethod { KEY, PASSWORD }

    public SshTargetConfig {
        host = requireNonBlank(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be 1-65535, got: " + port);
        }
        user = requireToken(user, "user");
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (maxConcurrentSessions < 1 || maxConcurrentSessions > 64) {
            throw new IllegalArgumentException(
                "maxConcurrentSessions must be 1-64, got: " + maxConcurrentSessions);
        }
        if (authMethod == AuthMethod.KEY) {
            if (identityFile == null) {
                throw new IllegalArgumentException("identityFile is required for KEY auth");
            }
            if (!Files.isRegularFile(identityFile)) {
                throw new IllegalArgumentException(
                    "identityFile does not exist: " + identityFile);
            }
            password = null;
        } else {
            identityFile = null;
            password = requireNonBlank(password, "password");
        }
        if (knownHostsFile != null && !Files.isRegularFile(knownHostsFile)) {
            // known_hosts may not exist yet on first run; only validate if it does exist
            if (Files.exists(knownHostsFile)) {
                throw new IllegalArgumentException(
                    "knownHostsFile exists but is not a regular file: " + knownHostsFile);
            }
        }
    }

    /** Build from environment variables for zero-code configuration. */
    public static SshTargetConfig fromEnvironment(Map<String, String> env) {
        String host = required(env, "CLAWKIT_OPS_SSH_HOST");
        int port = Integer.parseInt(env.getOrDefault("CLAWKIT_OPS_SSH_PORT", "22"));
        String user = env.getOrDefault("CLAWKIT_OPS_SSH_USER", "opsro");
        String method = env.getOrDefault("CLAWKIT_OPS_SSH_AUTH", "KEY");
        AuthMethod authMethod;
        Path identityFile = null;
        String password = null;

        if ("PASSWORD".equalsIgnoreCase(method)) {
            authMethod = AuthMethod.PASSWORD;
            password = required(env, "CLAWKIT_OPS_SSH_PASSWORD");
        } else {
            authMethod = AuthMethod.KEY;
            String keyPath = env.getOrDefault(
                "CLAWKIT_OPS_SSH_KEY",
                System.getProperty("user.home") + "/.ssh/id_ed25519_clawkit");
            identityFile = Path.of(keyPath);
        }

        Duration connectTimeout = Duration.ofSeconds(
            Long.parseLong(env.getOrDefault("CLAWKIT_OPS_SSH_TIMEOUT", "10")));
        int maxSessions = Integer.parseInt(
            env.getOrDefault("CLAWKIT_OPS_SSH_MAX_SESSIONS", "8"));
        boolean strict = !"false".equalsIgnoreCase(
            env.getOrDefault("CLAWKIT_OPS_SSH_STRICT_HOST_KEY", "true"));
        Path knownHosts = null;
        String kh = env.get("CLAWKIT_OPS_SSH_KNOWN_HOSTS");
        if (kh != null && !kh.isBlank()) {
            knownHosts = Path.of(kh);
        }
        return new SshTargetConfig(host, port, user, authMethod, identityFile,
            password, connectTimeout, maxSessions, strict, knownHosts);
    }

    public List<String> sshBaseCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=" + (strictHostKeyChecking ? "yes" : "accept-new"));
        cmd.add("-o");
        cmd.add("UserKnownHostsFile=" + (knownHostsFile != null
            ? knownHostsFile.toAbsolutePath().toString()
            : defaultKnownHosts()));
        cmd.add("-o");
        cmd.add("ConnectTimeout=" + connectTimeout.toSeconds());
        cmd.add("-o");
        cmd.add("BatchMode=yes");
        cmd.add("-o");
        cmd.add("ControlMaster=auto");
        cmd.add("-o");
        cmd.add("ControlPath=" + controlPath());
        cmd.add("-o");
        cmd.add("ControlPersist=60");
        cmd.add("-p");
        cmd.add(Integer.toString(port));
        if (authMethod == AuthMethod.KEY) {
            cmd.add("-i");
            cmd.add(identityFile.toAbsolutePath().toString());
        }
        cmd.add(user + "@" + host);
        return List.copyOf(cmd);
    }

    private String controlPath() {
        String sanitized = (user + "@" + host + "-p" + port)
            .replaceAll("[^a-zA-Z0-9._@\\-]", "_");
        return Path.of(System.getProperty("java.io.tmpdir"),
            "clawkit-ssh-" + sanitized).toString();
    }

    private static String defaultKnownHosts() {
        return Path.of(System.getProperty("user.home"),
            ".ssh/known_hosts").toString();
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing environment variable: " + name);
        }
        return value;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireToken(String value, String field) {
        if (value == null || !value.matches("[a-zA-Z_][a-zA-Z0-9_.-]*")) {
            throw new IllegalArgumentException(
                field + " contains unsupported characters: " + value);
        }
        return value;
    }
}
