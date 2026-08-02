package com.clawkit.tools.remote;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared SSH safety parameters enforced for every remote connection.
 *
 * <p>All arguments are inserted before the destination/alias to ensure
 * command-line precedence. Every argument is a separate list element —
 * no shell string concatenation or interpolation.
 *
 * <h3>Mandatory safety overrides</h3>
 * <ul>
 *   <li>BatchMode=yes — no interactive prompts</li>
 *   <li>PasswordAuthentication=no</li>
 *   <li>KbdInteractiveAuthentication=no</li>
 *   <li>PreferredAuthentications=publickey</li>
 *   <li>StrictHostKeyChecking=yes</li>
 *   <li>-T / RequestTTY=no</li>
 *   <li>ClearAllForwardings=yes</li>
 *   <li>ForwardAgent=no</li>
 *   <li>ForwardX11=no</li>
 *   <li>PermitLocalCommand=no</li>
 *   <li>ControlMaster=no</li>
 *   <li>ControlPath=none</li>
 *   <li>ControlPersist=no</li>
 *   <li>Tunnel=no</li>
 *   <li>AddKeysToAgent=no</li>
 *   <li>Bounded ServerAliveInterval + ServerAliveCountMax</li>
 * </ul>
 *
 * <p>Design: PRODUCT-1 §6.2.
 */
public final class RemoteSshSafetyPolicy {

    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int SERVER_ALIVE_INTERVAL = 10;
    private static final int SERVER_ALIVE_COUNT_MAX = 2;

    private RemoteSshSafetyPolicy() {
        // utility class
    }

    /**
     * Produce the safety prefix arguments that must appear before
     * connection-spec arguments.
     *
     * @param connectTimeoutSeconds resolved connect timeout in seconds (≥ 1)
     * @return unmodifiable argument list
     */
    public static List<String> safetyArgs(int connectTimeoutSeconds) {
        int ct = Math.max(1, connectTimeoutSeconds);
        List<String> args = new ArrayList<>();
        args.add("-T");
        args.add("-o"); args.add("BatchMode=yes");
        args.add("-o"); args.add("PasswordAuthentication=no");
        args.add("-o"); args.add("KbdInteractiveAuthentication=no");
        args.add("-o"); args.add("PreferredAuthentications=publickey");
        args.add("-o"); args.add("StrictHostKeyChecking=yes");
        args.add("-o"); args.add("ClearAllForwardings=yes");
        args.add("-o"); args.add("ForwardAgent=no");
        args.add("-o"); args.add("ForwardX11=no");
        args.add("-o"); args.add("PermitLocalCommand=no");
        args.add("-o"); args.add("ControlMaster=no");
        args.add("-o"); args.add("ControlPath=none");
        args.add("-o"); args.add("ControlPersist=no");
        args.add("-o"); args.add("Tunnel=no");
        args.add("-o"); args.add("AddKeysToAgent=no");
        args.add("-o"); args.add("ConnectTimeout=" + ct);
        args.add("-o"); args.add("ServerAliveInterval=" + SERVER_ALIVE_INTERVAL);
        args.add("-o"); args.add("ServerAliveCountMax=" + SERVER_ALIVE_COUNT_MAX);
        return List.copyOf(args);
    }

    /**
     * Produce safety prefix with the default connect timeout.
     */
    public static List<String> safetyArgs() {
        return safetyArgs(DEFAULT_CONNECT_TIMEOUT_SECONDS);
    }

    /**
     * Validate that an alias string is safe to pass to {@code ssh}.
     *
     * <p>Allowed: {@code [A-Za-z0-9][A-Za-z0-9._:-]{0,127}}
     * Rejected: leading {@code -}, whitespace, control characters, newlines.
     *
     * @param alias the alias to validate
     * @throws IllegalArgumentException if the alias is unsafe
     */
    public static void validateAlias(String alias) {
        if (alias == null || alias.isEmpty()) {
            throw new IllegalArgumentException("alias must not be empty");
        }
        if (alias.length() > 128) {
            throw new IllegalArgumentException("alias too long (max 128): " + alias.length());
        }
        if (!alias.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(
                "alias contains unsafe characters: " + alias);
        }
    }

    /**
     * Validate a targetId string for use in stores and registries.
     *
     * <p>Allowed: {@code [a-z0-9][a-z0-9_-]{0,62}}
     */
    public static void validateTargetId(String targetId) {
        if (targetId == null || targetId.isEmpty()) {
            throw new IllegalArgumentException("targetId must not be empty");
        }
        if (!targetId.matches("[a-z0-9][a-z0-9_-]{0,62}")) {
            throw new IllegalArgumentException(
                "targetId must match [a-z0-9][a-z0-9_-]{0,62}: " + targetId);
        }
    }
}
