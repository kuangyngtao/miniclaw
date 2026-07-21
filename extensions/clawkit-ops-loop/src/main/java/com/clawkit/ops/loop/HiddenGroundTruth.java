package com.clawkit.ops.loop;

import java.util.Set;

public record HiddenGroundTruth(
    String secretCaseId,
    String rootCauseCode,
    Set<EvidenceType> requiredEvidence,
    Set<String> prohibitedTools,
    String injection
) {
    public HiddenGroundTruth {
        requiredEvidence = Set.copyOf(requiredEvidence);
        prohibitedTools = Set.copyOf(prohibitedTools);
    }
}
