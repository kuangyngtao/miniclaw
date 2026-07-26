package com.clawkit.ops.loop;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single evidence collection specification within a {@link DiscoveryProfile}.
 *
 * <p>Design doc §8.1, PR-M3 §5. Each spec declares the tool, its fixed
 * arguments (from the versioned profile, never from a model), evidence
 * type, whether required/optional, timeout, and freshness TTL.
 */
public record EvidenceSpec(
    String toolName,
    EvidenceType evidenceType,
    String scope,
    String mcpTool,
    int sequenceNumber,
    Duration timeout,
    Duration freshnessTtl,
    boolean required,
    Map<String, Object> arguments
) {
    public EvidenceSpec {
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName");
        if (evidenceType == null) throw new IllegalArgumentException("evidenceType");
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("scope");
        if (mcpTool == null || mcpTool.isBlank()) throw new IllegalArgumentException("mcpTool");
        if (sequenceNumber < 1) throw new IllegalArgumentException("sequenceNumber >= 1");
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("timeout > 0");
        if (freshnessTtl == null || freshnessTtl.isNegative() || freshnessTtl.isZero())
            throw new IllegalArgumentException("freshnessTtl > 0");
        arguments = arguments == null || arguments.isEmpty()
            ? Map.of()
            : Map.copyOf(new LinkedHashMap<>(arguments));
    }

    // Backward-compatible factories (no arguments)
    public static EvidenceSpec required(
        String toolName, EvidenceType evidenceType, String scope,
        String mcpTool, int seq, Duration timeout, Duration freshnessTtl
    ) {
        return new EvidenceSpec(toolName, evidenceType, scope, mcpTool, seq,
            timeout, freshnessTtl, true, Map.of());
    }

    public static EvidenceSpec optional(
        String toolName, EvidenceType evidenceType, String scope,
        String mcpTool, int seq, Duration timeout, Duration freshnessTtl
    ) {
        return new EvidenceSpec(toolName, evidenceType, scope, mcpTool, seq,
            timeout, freshnessTtl, false, Map.of());
    }

    // New factories with fixed arguments (PR-M3 §5.1)
    public static EvidenceSpec required(String toolName, EvidenceType evidenceType,
        String scope, String mcpTool, int seq, Duration timeout, Duration freshnessTtl,
        Map<String, Object> arguments
    ) {
        return new EvidenceSpec(toolName, evidenceType, scope, mcpTool, seq,
            timeout, freshnessTtl, true, arguments);
    }

    public static EvidenceSpec optional(String toolName, EvidenceType evidenceType,
        String scope, String mcpTool, int seq, Duration timeout, Duration freshnessTtl,
        Map<String, Object> arguments
    ) {
        return new EvidenceSpec(toolName, evidenceType, scope, mcpTool, seq,
            timeout, freshnessTtl, false, arguments);
    }
}
