package com.clawkit.ops.loop;

import java.time.Duration;

/**
 * A single evidence collection specification within a {@link DiscoveryProfile}.
 *
 * <p>Design doc §8.1. Each spec declares the tool to call, the parameters,
 * the evidence type, whether it is required or optional, an execution
 * timeout, and a freshness TTL.
 */
public record EvidenceSpec(
    String toolName,
    EvidenceType evidenceType,
    String scope,
    String mcpTool,
    int sequenceNumber,
    Duration timeout,
    Duration freshnessTtl,
    boolean required
) {
    public EvidenceSpec {
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName must not be blank");
        if (evidenceType == null) throw new IllegalArgumentException("evidenceType must not be null");
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("scope must not be blank");
        if (mcpTool == null || mcpTool.isBlank()) throw new IllegalArgumentException("mcpTool must not be blank");
        if (sequenceNumber < 1) throw new IllegalArgumentException("sequenceNumber must be >= 1");
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("timeout must be positive");
        if (freshnessTtl == null || freshnessTtl.isNegative() || freshnessTtl.isZero())
            throw new IllegalArgumentException("freshnessTtl must be positive");
    }

    public static EvidenceSpec required(
        String toolName, EvidenceType evidenceType, String scope,
        String mcpTool, int seq, Duration timeout, Duration freshnessTtl
    ) {
        return new EvidenceSpec(toolName, evidenceType, scope, mcpTool, seq,
            timeout, freshnessTtl, true);
    }

    public static EvidenceSpec optional(
        String toolName, EvidenceType evidenceType, String scope,
        String mcpTool, int seq, Duration timeout, Duration freshnessTtl
    ) {
        return new EvidenceSpec(toolName, evidenceType, scope, mcpTool, seq,
            timeout, freshnessTtl, false);
    }
}
