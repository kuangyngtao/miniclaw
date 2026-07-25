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
    boolean claimedResolved,
    String schemaVersion,
    DiagnosisStatus diagnosisStatus,
    CurrentCondition currentCondition,
    java.time.Instant evaluatedAt,
    ResolutionAttribution resolutionAttribution
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
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "1" : schemaVersion;
        diagnosisStatus = diagnosisStatus == null
            ? ("INCONCLUSIVE".equals(rootCauseCode)
                ? DiagnosisStatus.INCONCLUSIVE : DiagnosisStatus.PROBABLE)
            : diagnosisStatus;
        currentCondition = currentCondition == null ? CurrentCondition.UNKNOWN : currentCondition;
        resolutionAttribution = resolutionAttribution == null
            ? ResolutionAttribution.NONE : resolutionAttribution;
    }

    public Diagnosis(
        String rootCauseCode, double confidence, List<String> supportingEvidence,
        List<String> contradictingEvidence, List<String> alternatives,
        List<String> missingEvidence, String recommendedActionCode,
        boolean claimedResolved
    ) {
        this(rootCauseCode, confidence, supportingEvidence, contradictingEvidence,
            alternatives, missingEvidence, recommendedActionCode, claimedResolved,
            "1", null, null, null, ResolutionAttribution.NONE);
    }

    public enum DiagnosisStatus { CONFIRMED, PROBABLE, INCONCLUSIVE }
    public enum CurrentCondition { ACTIVE, RECOVERED, UNKNOWN }
    public enum ResolutionAttribution { NONE, SELF_RECOVERED }
}
