package com.clawkit.tools.remote;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Structured error for remote operations.
 *
 * <p>Every error has a machine-readable {@code code}, a safe message suitable
 * for display and reports (never contains raw SSH stderr or key paths), and
 * a retryability flag.
 *
 * <p>Design: REMOTE-0 §13.
 */
public record RemoteError(
    String code,
    String safeMessage,
    boolean retryable,
    Map<String, String> safeDetails,
    Instant at
) {
    public RemoteError {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) throw new IllegalArgumentException("code must not be blank");
        if (safeMessage == null) safeMessage = code;
        if (safeDetails == null) safeDetails = Map.of();
        if (at == null) at = Instant.now();
    }

    // ── Factory methods ──────────────────────────────────────────────

    public static RemoteError of(String code, String safeMessage, boolean retryable) {
        return new RemoteError(code, safeMessage, retryable, Map.of(), Instant.now());
    }

    public static RemoteError of(String code, String safeMessage, boolean retryable,
                                  Map<String, String> details) {
        return new RemoteError(code, safeMessage, retryable, details, Instant.now());
    }

    // ── RMT-001 TARGET_NOT_FOUND ─────────────────────────────────────
    public static RemoteError targetNotFound(String targetId) {
        return new RemoteError("RMT-001", "target not found: " + targetId,
            false, Map.of("targetId", targetId), Instant.now());
    }

    // ── RMT-002 TARGET_CONFIG_INVALID ─────────────────────────────────
    public static RemoteError targetConfigInvalid(String targetId, String detail) {
        return new RemoteError("RMT-002", "target config invalid: " + detail,
            false, Map.of("targetId", targetId), Instant.now());
    }

    // ── RMT-003 CREDENTIAL_REF_UNRESOLVED ─────────────────────────────
    public static RemoteError credentialRefUnresolved(String ref, String detail) {
        return new RemoteError("RMT-003", "credential ref unresolved: " + detail,
            false, Map.of("ref", ref), Instant.now());
    }

    // ── RMT-004 HOST_KEY_REJECTED ─────────────────────────────────────
    public static RemoteError hostKeyRejected(String targetId) {
        return new RemoteError("RMT-004", "remote host key not recognized",
            false, Map.of("targetId", targetId), Instant.now());
    }

    // ── RMT-005 SSH_AUTH_FAILED ───────────────────────────────────────
    public static RemoteError sshAuthFailed(String targetId) {
        return new RemoteError("RMT-005", "SSH authentication failed",
            false, Map.of("targetId", targetId), Instant.now());
    }

    // ── RMT-006 REMOTE_UNREACHABLE ────────────────────────────────────
    public static RemoteError remoteUnreachable(String targetId, long latencyMs) {
        return new RemoteError("RMT-006", "remote unreachable",
            true, Map.of("targetId", targetId, "latencyMs", String.valueOf(latencyMs)), Instant.now());
    }

    // ── RMT-007 MCP_PROTOCOL_MISMATCH ─────────────────────────────────
    public static RemoteError mcpProtocolMismatch(String targetId,
                                                   String expected, String actual) {
        return new RemoteError("RMT-007", "MCP protocol mismatch: expected "
            + expected + ", got " + actual, false,
            Map.of("targetId", targetId, "expected", expected, "actual", actual), Instant.now());
    }

    // ── RMT-008 SERVER_IDENTITY_MISMATCH ──────────────────────────────
    public static RemoteError serverIdentityMismatch(String targetId,
                                                      String field, String expected, String actual) {
        return new RemoteError("RMT-008", "server identity mismatch: " + field
            + " expected " + expected + " but got " + actual, false,
            Map.of("targetId", targetId, "field", field, "expected", expected, "actual", actual), Instant.now());
    }

    // ── RMT-009 CAPABILITY_PROFILE_MISMATCH ───────────────────────────
    public static RemoteError capabilityProfileMismatch(String targetId,
                                                         String expected, String actual) {
        return new RemoteError("RMT-009", "capability profile mismatch: expected "
            + expected + " but got " + actual, false,
            Map.of("targetId", targetId, "expected", expected, "actual", actual), Instant.now());
    }

    // ── RMT-010 TOOL_CONTRACT_MISMATCH ────────────────────────────────
    public static RemoteError toolContractMismatch(String targetId,
                                                    String hashType, String expected, String actual) {
        return new RemoteError("RMT-010", "tool contract mismatch (" + hashType
            + "): expected " + expected + " but got " + actual, false,
            Map.of("targetId", targetId, "hashType", hashType, "expected", expected, "actual", actual),
            Instant.now());
    }

    // ── RMT-011 UNSAFE_TOOL_ANNOTATION ────────────────────────────────
    public static RemoteError unsafeToolAnnotation(String targetId, String toolName) {
        return new RemoteError("RMT-011", "unsafe tool annotation: " + toolName,
            false, Map.of("targetId", targetId, "tool", toolName), Instant.now());
    }

    // ── RMT-012 TOOL_NAMESPACE_COLLISION ──────────────────────────────
    public static RemoteError toolNamespaceCollision(String targetId, String toolName) {
        return new RemoteError("RMT-012", "tool namespace collision: " + toolName,
            false, Map.of("targetId", targetId, "tool", toolName), Instant.now());
    }

    // ── RMT-013 REMOTE_NOT_READY ──────────────────────────────────────
    public static RemoteError remoteNotReady(String targetId, long currentGen, long expectedGen) {
        return new RemoteError("RMT-013", "remote not ready",
            true, Map.of("targetId", targetId,
                "currentGeneration", String.valueOf(currentGen),
                "expectedGeneration", String.valueOf(expectedGen)),
            Instant.now());
    }

    public static RemoteError staleRemoteTool(String targetId, long toolGen, long currentGen) {
        return new RemoteError("RMT-013", "stale remote tool: generation "
            + toolGen + " != current " + currentGen, true,
            Map.of("targetId", targetId,
                "toolGeneration", String.valueOf(toolGen),
                "currentGeneration", String.valueOf(currentGen)),
            Instant.now());
    }

    // ── RMT-014 OUTPUT_REJECTED ───────────────────────────────────────
    public static RemoteError outputRejected(String targetId, String reason) {
        return new RemoteError("RMT-014", "remote output rejected: " + reason,
            false, Map.of("targetId", targetId), Instant.now());
    }

    // ── RMT-015 ACTIVE_TARGET_EXISTS ──────────────────────────────────
    public static RemoteError activeTargetExists(String existingTargetId) {
        return new RemoteError("RMT-015", "another target is already active: "
            + existingTargetId, false, Map.of("existingTargetId", existingTargetId), Instant.now());
    }

    // ── Additional convenience ────────────────────────────────────────

    public RemoteError withDetail(String key, String value) {
        var merged = new LinkedHashMap<>(safeDetails);
        merged.put(key, value);
        return new RemoteError(code, safeMessage, retryable,
            Collections.unmodifiableMap(merged), at);
    }
}
