package com.clawkit.ops.loop;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OpsBlindAgentMainTest {
    @Test
    void usesProductApiKeyAndProviderDefaults() {
        var config = OpsBlindAgentMain.modelConfig(Map.of("CLAWKIT_API_KEY", "test-secret"));

        assertThat(config.apiKey()).isEqualTo("test-secret");
        assertThat(config.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(config.model()).isEqualTo("deepseek-v4-flash");
        assertThat(config.requestTimeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(config.maxRetries()).isEqualTo(2);
    }

    @Test
    void acceptsProductModelOverride() {
        var config = OpsBlindAgentMain.modelConfig(Map.of(
            "CLAWKIT_API_KEY", "test-secret",
            "CLAWKIT_MODEL", " deepseek-reasoner "));

        assertThat(config.model()).isEqualTo("deepseek-reasoner");
    }

    @Test
    void rejectsMissingProductApiKey() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> OpsBlindAgentMain.modelConfig(Map.of()))
            .withMessage("missing environment variable: CLAWKIT_API_KEY");
    }

    @Test
    void exposesOnlyStructuredDiagnosisSubmissionAfterBaselineCollection() {
        var registry = OpsBlindAgentMain.diagnosisRegistry(new DiagnosisSubmissionTool());

        assertThat(registry.getAvailableTools())
            .extracting(com.clawkit.tools.schema.ToolDefinition::name)
            .containsExactly("submit_diagnosis");
    }

    @Test
    void normalizesVerboseEvidenceReferencesWithoutInventingIds() throws Exception {
        Diagnosis diagnosis = OpsBlindAgentMain.parseDiagnosis("""
            preface
            {"rootCauseCode":"DB_LOCK_WAIT","confidence":0.9,
             "supportingEvidence":[{"evidenceId":"e-1","reason":"lock graph"}],
             "contradictingEvidence":[],"alternatives":[],"missingEvidence":[],
             "recommendedActionCode":"ESCALATE","claimedResolved":false,
             "diagnosisStatus":"CONFIRMED","currentCondition":"ACTIVE",
             "resolutionAttribution":"NONE"}
            """);

        assertThat(diagnosis.supportingEvidence()).containsExactly("e-1");
    }

    @Test
    void defaultsOmittedListFieldsToEmptyCollections() throws Exception {
        Diagnosis diagnosis = OpsBlindAgentMain.parseDiagnosis("""
            {"rootCauseCode":"INCONCLUSIVE","confidence":0.2,
             "recommendedActionCode":"ESCALATE","claimedResolved":false,
             "diagnosisStatus":"INCONCLUSIVE","currentCondition":"UNKNOWN",
             "resolutionAttribution":"NONE"}
            """);

        assertThat(diagnosis.supportingEvidence()).isEmpty();
        assertThat(diagnosis.contradictingEvidence()).isEmpty();
        assertThat(diagnosis.alternatives()).isEmpty();
        assertThat(diagnosis.missingEvidence()).isEmpty();
    }

    @Test
    void ignoresUnknownModelExtensionFields() throws Exception {
        Diagnosis diagnosis = OpsBlindAgentMain.parseDiagnosis("""
            {"rootCauseCode":"INCONCLUSIVE","confidence":0.2,
             "supportingEvidence":[],"contradictingEvidence":[],
             "alternatives":[],"missingEvidence":[],
             "recommendedActionCode":"ESCALATE","claimedResolved":false,
             "diagnosisStatus":"INCONCLUSIVE","currentCondition":"UNKNOWN",
             "resolutionAttribution":"NONE","schema":{"type":"object"}}
            """);

        assertThat(diagnosis.rootCauseCode()).isEqualTo("INCONCLUSIVE");
    }

    @Test
    void normalizesQualitativeConfidenceAndScalarList() throws Exception {
        Diagnosis diagnosis = OpsBlindAgentMain.parseDiagnosis("""
            {"rootCauseCode":"DB_LOCK_WAIT","confidence":"PROBABLE",
             "supportingEvidence":["e-1"],"contradictingEvidence":[],
             "alternatives":["CPU_PRESSURE"],"missingEvidence":"query text unavailable",
             "recommendedActionCode":"ESCALATE","claimedResolved":false,
             "diagnosisStatus":"CONFIRMED","currentCondition":"ACTIVE",
             "resolutionAttribution":"NONE"}
            """);

        assertThat(diagnosis.confidence()).isEqualTo(0.7);
        assertThat(diagnosis.missingEvidence()).containsExactly("query text unavailable");
    }

    @Test
    void normalizesPercentageConfidence() throws Exception {
        Diagnosis diagnosis = OpsBlindAgentMain.parseDiagnosis("""
            {"rootCauseCode":"INCONCLUSIVE","confidence":65,
             "supportingEvidence":["e-1"],"contradictingEvidence":[],
             "alternatives":[],"missingEvidence":[],
             "recommendedActionCode":"ESCALATE","claimedResolved":false,
             "diagnosisStatus":"INCONCLUSIVE","currentCondition":"ACTIVE",
             "resolutionAttribution":"NONE"}
            """);

        assertThat(diagnosis.confidence()).isEqualTo(0.65);
    }

    @Test
    void normalizesVerboseAlternativeRootCauses() throws Exception {
        Diagnosis diagnosis = OpsBlindAgentMain.parseDiagnosis("""
            {"rootCauseCode":"DB_LOCK_WAIT","confidence":0.9,
             "supportingEvidence":["e-1"],"contradictingEvidence":[],
             "alternatives":[{"rootCauseCode":"CPU_PRESSURE","reason":"resources nominal"}],
             "missingEvidence":[],"recommendedActionCode":"ESCALATE","claimedResolved":false,
             "diagnosisStatus":"CONFIRMED","currentCondition":"ACTIVE",
             "resolutionAttribution":"NONE"}
            """);

        assertThat(diagnosis.alternatives()).containsExactly("CPU_PRESSURE");
    }

    @Test
    void usesLastFencedJsonAsModelFinalRevision() throws Exception {
        String response = """
            ```json
            {"rootCauseCode":"INCONCLUSIVE","confidence":0.1}
            ```
            revised:
            ```json
            {"rootCauseCode":"DB_LOCK_WAIT","confidence":0.9,
             "supportingEvidence":[],"contradictingEvidence":[],"alternatives":[],
             "missingEvidence":[],"recommendedActionCode":"ESCALATE","claimedResolved":false}
            ```
            """;

        assertThat(OpsBlindAgentMain.parseDiagnosis(response).rootCauseCode())
            .isEqualTo("DB_LOCK_WAIT");
    }

    @Test
    void excludesExpiredSupportingEvidenceButKeepsCurrentEvidence() {
        Instant now = Instant.parse("2026-07-21T00:01:00Z");
        Diagnosis diagnosis = new Diagnosis("DB_LOCK_WAIT", 0.9, List.of("old", "current"),
            List.of(), List.of("CPU_PRESSURE"), List.of(), "ESCALATE", false);
        var fact = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("success", true);
        Evidence old = new Evidence("old", "inc", EvidenceType.LOGS, "source", now.minusSeconds(60),
            now.minusSeconds(30), "scope", Evidence.Kind.FACT, fact, "run://r/tool/old",
            Evidence.Freshness.CURRENT, Evidence.Redaction.NONE, "2",
            Evidence.CollectionStatus.OBSERVED, now.minusSeconds(1), null);
        Evidence current = new Evidence("current", "inc", EvidenceType.DB_LOCK_GRAPH, "source", now,
            now, "scope", Evidence.Kind.FACT, fact, "run://r/tool/current",
            Evidence.Freshness.CURRENT, Evidence.Redaction.NONE, "2",
            Evidence.CollectionStatus.OBSERVED, now.plusSeconds(30), null);

        Diagnosis sanitized = OpsBlindAgentMain.sanitizeSupportingEvidence(
            diagnosis, List.of(old, current), now);

        assertThat(sanitized.supportingEvidence()).containsExactly("current");
        assertThat(sanitized.missingEvidence()).singleElement().asString().contains("old");
    }
}
