package com.clawkit.ops.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record OpsToolResult(
    String tool,
    String target,
    Instant observedAt,
    Instant collectedAt,
    boolean current,
    boolean success,
    JsonNode data,
    String errorCode,
    String error,
    Audit audit
) {
    public record Audit(
        String backend,
        long durationMs,
        int timeoutMs,
        long totalOutputBytes,
        int returnedOutputBytes,
        boolean truncated
    ) {}
}
