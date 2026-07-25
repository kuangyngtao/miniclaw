package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisReconcilerTest {
    private static final Instant NOW = Instant.parse("2026-07-22T00:00:30Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void correctsInconclusiveModelWhenApplicationPoolSaturationIsDirectlyObserved() {
        Diagnosis model = diagnosis("INCONCLUSIVE", Diagnosis.CurrentCondition.ACTIVE);
        DiagnosticSignals signals = new DiagnosticSignals("CONNECTION_EXHAUSTION", "ACTIVE",
            true, false, true, false, false, 10, List.of("metric"));

        Diagnosis result = DiagnosisReconciler.reconcile(model, signals,
            List.of(evidence("metric", EvidenceType.BUSINESS_METRIC)), NOW);

        assertThat(result.rootCauseCode()).isEqualTo("CONNECTION_EXHAUSTION");
        assertThat(result.diagnosisStatus()).isEqualTo(Diagnosis.DiagnosisStatus.CONFIRMED);
        assertThat(result.supportingEvidence()).contains("metric");
        assertThat(result.alternatives()).contains("INCONCLUSIVE");
    }

    @Test
    void preservesAmbiguousRootCauseWhileAddingBaselineEvidenceCoverage() {
        Diagnosis base = diagnosis("INCONCLUSIVE", Diagnosis.CurrentCondition.ACTIVE);
        Diagnosis model = new Diagnosis(base.rootCauseCode(), base.confidence(), List.of(),
            List.of("metric"), base.alternatives(), base.missingEvidence(),
            base.recommendedActionCode(), false, "2", base.diagnosisStatus(),
            base.currentCondition(), NOW, base.resolutionAttribution());
        DiagnosticSignals signals = new DiagnosticSignals("INCONCLUSIVE", "ACTIVE",
            true, false, false, false, false, 5, List.of("metric"));

        Diagnosis result = DiagnosisReconciler.reconcile(model, signals,
            List.of(evidence("metric", EvidenceType.BUSINESS_METRIC)), NOW);

        assertThat(result.rootCauseCode()).isEqualTo("INCONCLUSIVE");
        assertThat(result.supportingEvidence()).contains("metric");
        assertThat(result.contradictingEvidence()).doesNotContain("metric");
    }

    private static Diagnosis diagnosis(String root, Diagnosis.CurrentCondition condition) {
        return new Diagnosis(root, 0.5, List.of(), List.of(), List.of(), List.of(),
            "ESCALATE", false, "2", Diagnosis.DiagnosisStatus.INCONCLUSIVE,
            condition, NOW, Diagnosis.ResolutionAttribution.NONE);
    }

    private static Evidence evidence(String id, EvidenceType type) {
        var fact = JSON.createObjectNode().put("success", true);
        fact.set("data", JSON.createObjectNode());
        return new Evidence(id, "incident", type, "test", NOW.minusSeconds(5),
            NOW.minusSeconds(5), "scope", Evidence.Kind.FACT, fact,
            "run://baseline-incident/tool/" + id, Evidence.Freshness.CURRENT,
            Evidence.Redaction.NONE, "2", Evidence.CollectionStatus.OBSERVED,
            NOW.plusSeconds(60), null);
    }
}
