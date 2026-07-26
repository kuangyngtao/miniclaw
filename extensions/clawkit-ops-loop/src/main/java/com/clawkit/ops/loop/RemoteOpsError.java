package com.clawkit.ops.loop;

import java.time.Instant;

/**
 * Structured error from remote OPS operations.
 *
 * <p>Design doc §7.5. Each error carries a layer (which subsystem failed),
 * a machine-readable code, a safe message suitable for reports, and
 * retryability information.
 */
public record RemoteOpsError(
    Layer layer,
    String code,
    String safeMessage,
    boolean retryable,
    String targetId,
    Instant at,
    long durationMs
) {
    public enum Layer { LOCAL_CONFIG, SSH, GATEWAY, MCP, TOOL }

    public RemoteOpsError {
        if (layer == null) throw new IllegalArgumentException("layer must not be null");
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
        if (safeMessage == null) safeMessage = code;
        if (targetId == null || targetId.isBlank()) throw new IllegalArgumentException("targetId must not be blank");
        if (at == null) at = Instant.now();
    }

    // ── Factory methods for each error code (§7.5) ──

    public static RemoteOpsError sshClientMissing(String targetId, String detail) {
        return new RemoteOpsError(Layer.LOCAL_CONFIG, "SSH_CLIENT_MISSING",
            "ssh client not found: " + detail, false, targetId, Instant.now(), 0);
    }

    public static RemoteOpsError sshKeyUnreadable(String targetId, String detail) {
        return new RemoteOpsError(Layer.LOCAL_CONFIG, "SSH_KEY_UNREADABLE",
            detail, false, targetId, Instant.now(), 0);
    }

    public static RemoteOpsError sshHostKeyRejected(String targetId, String safeRef) {
        return new RemoteOpsError(Layer.SSH, "SSH_HOST_KEY_REJECTED",
            "remote host key not recognized", false, targetId, Instant.now(), 0);
    }

    public static RemoteOpsError sshDnsFailed(String targetId, String safeRef) {
        return new RemoteOpsError(Layer.SSH, "SSH_DNS_FAILED",
            "could not resolve remote host", true, targetId, Instant.now(), 0);
    }

    public static RemoteOpsError sshConnectionRefused(String targetId, Instant at, long durationMs) {
        return new RemoteOpsError(Layer.SSH, "SSH_CONNECTION_REFUSED",
            "remote SSH port not accepting connections", true, targetId, at, durationMs);
    }

    public static RemoteOpsError sshConnectTimeout(String targetId, Instant at, long durationMs) {
        return new RemoteOpsError(Layer.SSH, "SSH_CONNECT_TIMEOUT",
            "SSH connect timed out", true, targetId, at, durationMs);
    }

    public static RemoteOpsError sshAuthFailed(String targetId, Instant at, long durationMs) {
        return new RemoteOpsError(Layer.SSH, "SSH_AUTH_FAILED",
            "SSH authentication failed", false, targetId, at, durationMs);
    }

    public static RemoteOpsError sshTransportClosed(String targetId, Instant at, long durationMs, int exitCode) {
        return new RemoteOpsError(Layer.SSH, "SSH_TRANSPORT_CLOSED",
            "SSH transport closed unexpectedly (exit " + exitCode + ")", exitCode == 0, targetId, at, durationMs);
    }

    public static RemoteOpsError sshRequestTimeout(String targetId, Instant at, long durationMs) {
        return new RemoteOpsError(Layer.SSH, "SSH_REQUEST_TIMEOUT",
            "SSH request timed out; remote outcome is unknown", false, targetId, at, durationMs);
    }

    public static RemoteOpsError remoteGatewayRejected(String targetId) {
        return new RemoteOpsError(Layer.GATEWAY, "REMOTE_GATEWAY_REJECTED",
            "remote gateway did not accept the connection", false, targetId, Instant.now(), 0);
    }

    public static RemoteOpsError remoteMcpStartFailed(String targetId, String detail) {
        return new RemoteOpsError(Layer.MCP, "REMOTE_MCP_START_FAILED",
            "remote MCP server failed to start", true, targetId, Instant.now(), 0);
    }

    public static RemoteOpsError remoteMcpProtocolError(String targetId, String detail) {
        return new RemoteOpsError(Layer.MCP, "REMOTE_MCP_PROTOCOL_ERROR",
            "remote MCP protocol error", false, targetId, Instant.now(), 0);
    }

    public static RemoteOpsError remoteProfileMismatch(String targetId, String expected, String actual) {
        return new RemoteOpsError(Layer.MCP, "REMOTE_PROFILE_MISMATCH",
            "capability profile mismatch", false, targetId, Instant.now(), 0);
    }

    public static RemoteOpsError remoteToolsetMismatch(String targetId, String expected, String actual) {
        return new RemoteOpsError(Layer.MCP, "REMOTE_TOOLSET_MISMATCH",
            "tool-set hash mismatch", false, targetId, Instant.now(), 0);
    }

    public static RemoteOpsError remoteToolFailed(String targetId, String toolName, String detail) {
        return new RemoteOpsError(Layer.TOOL, "REMOTE_TOOL_FAILED",
            toolName + " failed: " + detail, true, targetId, Instant.now(), 0);
    }

    public static RemoteOpsError remoteOutputTruncated(String targetId, String toolName) {
        return new RemoteOpsError(Layer.TOOL, "REMOTE_OUTPUT_TRUNCATED",
            toolName + " output was truncated", false, targetId, Instant.now(), 0);
    }
}
