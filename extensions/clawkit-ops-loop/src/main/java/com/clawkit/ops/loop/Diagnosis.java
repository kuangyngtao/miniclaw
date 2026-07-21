package com.clawkit.ops.loop;

import java.util.List;

public record Diagnosis(
    String rootCauseCode,
    double confidence,
    List<String> supportingEvidence,
    List<String> contradictingEvidence,
    List<String> alternatives,
    List<String> missingEvidence,
    String recommendedActionCode,
    boolean claimedResolved
) {
    public Diagnosis {
        rootCauseCode = rootCauseCode == null || rootCauseCode.isBlank()
            ? "INCONCLUSIVE" : rootCauseCode;
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        supportingEvidence = List.copyOf(supportingEvidence);
        contradictingEvidence = List.copyOf(contradictingEvidence);
        alternatives = List.copyOf(alternatives);
        missingEvidence = List.copyOf(missingEvidence);
        recommendedActionCode = recommendedActionCode == null
            ? "ESCALATE" : recommendedActionCode;
    }
}
