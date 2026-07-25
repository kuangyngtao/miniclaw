package com.clawkit.ops.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Executes commands on a remote host via the system {@code ssh} CLI.
 *
 * <p>Connection multiplexing (ControlMaster) reduces per-command SSH handshake
 * latency. The {@link SshTargetConfig} supplies host, port, user, and key
 * identity; passphrases are handled by {@code ssh-agent}, never by this class.
 *
 * <p>Password authentication uses {@code sshpass} (optional dependency).
 * Prefer key auth — passwords passed via environment variable are visible
 * in the local process list.
 *
 * <h3>Error classification</h3>
 * <p>Exit codes and stderr patterns are mapped to structured
 * {@link CommandResult} fields so callers can distinguish connection
 * failures from command failures without parsing stderr.
 */
public final class SshCommandExecutor implements CommandExecutor {

    private static final int EXIT_CONNECTION = 255;
    private static final int EXIT_HOST_KEY = 255;

    private final SshTargetConfig config;
    private final CommandExecutor local;
    private final Semaphore concurrency;

    public SshCommandExecutor(SshTargetConfig config) {
        this(config, new ProcessCommandExecutor());
    }

    SshCommandExecutor(SshTargetConfig config, CommandExecutor local) {
        this.config = config;
        this.local = local;
        this.concurrency = new Semaphore(config.maxConcurrentSessions());
    }

    @Override
    public CommandResult execute(
        List<String> command,
        Map<String, String> environment,
        Duration timeout,
        int maxOutputBytes
    ) {
        if (!concurrency.tryAcquire()) {
            return new CommandResult(-1, "",
                "SSH session limit reached (max=" + config.maxConcurrentSessions() + ")",
                false, false, 0);
        }
        try {
            return doExecute(command, environment, timeout, maxOutputBytes);
        } finally {
            concurrency.release();
        }
    }

    private CommandResult doExecute(
        List<String> command,
        Map<String, String> environment,
        Duration timeout,
        int maxOutputBytes
    ) {
        List<String> fullCommand = buildFullCommand(command);
        Map<String, String> mergedEnv = mergeEnvironment(environment);
        long startNanos = System.nanoTime();

        // SSH commands can be slow; use the larger of connectTimeout and command timeout
        Duration effectiveTimeout = timeout.compareTo(config.connectTimeout()) > 0
            ? timeout : config.connectTimeout();
        // But never exceed 2x the original timeout for connection
        if (effectiveTimeout.compareTo(timeout.multipliedBy(2)) > 0) {
            effectiveTimeout = timeout.multipliedBy(2);
        }

        CommandResult raw = local.execute(fullCommand, mergedEnv,
            effectiveTimeout, maxOutputBytes);

        if (raw.timedOut()) {
            return new CommandResult(-1, raw.stdout(), raw.stderr(),
                true, raw.truncated(), raw.totalOutputBytes());
        }

        return classify(raw);
    }

    private List<String> buildFullCommand(List<String> remoteCommand) {
        List<String> cmd = new ArrayList<>();

        if (config.authMethod() == SshTargetConfig.AuthMethod.PASSWORD) {
            // sshpass reads password from SSHPASS env var
            cmd.add("sshpass");
            cmd.add("-e");
        }

        cmd.addAll(config.sshBaseCommand());

        // Join remote command with shell-safe quoting
        StringBuilder joined = new StringBuilder();
        for (String part : remoteCommand) {
            if (joined.length() > 0) joined.append(' ');
            joined.append(shellEscape(part));
        }
        cmd.add("--");
        cmd.add(joined.toString());

        return List.copyOf(cmd);
    }

    private Map<String, String> mergeEnvironment(Map<String, String> extra) {
        if (config.authMethod() != SshTargetConfig.AuthMethod.PASSWORD || extra.isEmpty()) {
            return extra;
        }
        // For PASSWORD auth, inject SSHPASS without modifying the input map
        var merged = new java.util.LinkedHashMap<>(extra);
        merged.put("SSHPASS", config.password());
        return Map.copyOf(merged);
    }

    /**
     * Classifies raw SSH results into structured error codes.
     *
     * <p>SSH uses exit code 255 for connection/auth/host-key failures.
     * We inspect stderr to distinguish the specific cause.
     */
    private CommandResult classify(CommandResult raw) {
        if (raw.success()) {
            return raw;
        }

        String stderr = raw.stderr();
        String stdout = raw.stdout();
        int exitCode = raw.exitCode();

        // Connection-level failures (ssh itself failed, not the remote command)
        if (exitCode == EXIT_CONNECTION) {
            String lower = stderr.toLowerCase();
            if (lower.contains("permission denied")
                || lower.contains("publickey")
                || lower.contains("authentication")) {
                return new CommandResult(exitCode, stdout,
                    classifyError("SSH_AUTH_FAILED", stderr),
                    false, raw.truncated(), raw.totalOutputBytes());
            }
            if (lower.contains("host key")
                || lower.contains("host identification")
                || lower.contains("known_hosts")
                || lower.contains("fingerprint")) {
                return new CommandResult(exitCode, stdout,
                    classifyError("SSH_HOST_KEY_REJECTED", stderr),
                    false, raw.truncated(), raw.totalOutputBytes());
            }
            if (lower.contains("connection refused")
                || lower.contains("connection timed out")
                || lower.contains("no route")
                || lower.contains("could not resolve")) {
                return new CommandResult(exitCode, stdout,
                    classifyError("SSH_CONNECTION_FAILED", stderr),
                    false, raw.truncated(), raw.totalOutputBytes());
            }
            return new CommandResult(exitCode, stdout,
                classifyError("SSH_TRANSPORT_FAILED", stderr),
                false, raw.truncated(), raw.totalOutputBytes());
        }

        // Remote command failed — pass through with full stderr
        if (exitCode == 127) {
            return new CommandResult(exitCode, stdout,
                classifyError("COMMAND_NOT_FOUND", stderr),
                false, raw.truncated(), raw.totalOutputBytes());
        }

        return raw; // non-zero but not an SSH transport error
    }

    private static String classifyError(String code, String stderr) {
        // Extract first meaningful line, strip timestamps and noise
        String firstLine = stderr.lines()
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .findFirst()
            .orElse(stderr.trim());
        int maxLen = 200;
        String detail = firstLine.length() > maxLen
            ? firstLine.substring(0, maxLen) + "..." : firstLine;
        return "[" + code + "] " + detail;
    }

    /**
     * Simple shell escaping for the remote command string.
     * Wraps arguments in single quotes, escaping any internal single quotes.
     */
    static String shellEscape(String arg) {
        if (arg.isEmpty()) {
            return "''";
        }
        // If no special characters, return as-is
        if (arg.matches("[a-zA-Z0-9_.,:/=@%+\\-]*")) {
            return arg;
        }
        // Wrap in single quotes, escape internal single quotes
        return "'" + arg.replace("'", "'\\''") + "'";
    }
}
