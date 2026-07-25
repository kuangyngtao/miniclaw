package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceV2Test {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void oldConstructorRetainsV1Compatibility() {
        var fact = MAPPER.createObjectNode().put("success", true);
        Evidence evidence = new Evidence("e-1", "inc", EvidenceType.LOGS, "source",
            Instant.EPOCH, Instant.EPOCH, "scope", Evidence.Kind.FACT, fact,
            "run://r/tool/t", Evidence.Freshness.CURRENT, Evidence.Redaction.NONE);

        assertThat(evidence.schemaVersion()).isEqualTo("1");
        assertThat(evidence.collectionStatus()).isEqualTo(Evidence.CollectionStatus.OBSERVED);
        assertThat(evidence.isCurrentAt(Instant.MAX)).isTrue();
    }

    @Test
    void validUntilMakesOtherwiseCurrentEvidenceUnusable() {
        Instant observed = Instant.parse("2026-07-21T00:00:00Z");
        var fact = MAPPER.createObjectNode().put("success", true);
        Evidence evidence = new Evidence("e-1", "inc", EvidenceType.DB_LOCK_GRAPH,
            "source", observed, observed, "scope", Evidence.Kind.FACT, fact,
            "run://r/tool/t", Evidence.Freshness.CURRENT,
            Evidence.Redaction.SENSITIVE_FIELDS_REMOVED, "2",
            Evidence.CollectionStatus.OBSERVED, observed.plusSeconds(30), null);

        assertThat(evidence.isCurrentAt(observed.plusSeconds(30))).isTrue();
        assertThat(evidence.isCurrentAt(observed.plusSeconds(31))).isFalse();
    }

    @Test
    void missingV2FieldsInSerializedV1ReportReceiveCompatibilityDefaults() throws Exception {
        var fact = MAPPER.createObjectNode().put("success", true);
        Evidence evidence = new Evidence("e-1", "inc", EvidenceType.LOGS, "source",
            Instant.EPOCH, Instant.EPOCH, "scope", Evidence.Kind.FACT, fact,
            "run://run/tool/t", Evidence.Freshness.CURRENT, Evidence.Redaction.NONE);
        Diagnosis diagnosis = new Diagnosis("CAUSE", .8, List.of("e-1"), List.of(),
            List.of(), List.of(), "ESCALATE", false);
        IncidentReport report = new IncidentReport("inc", "run", IncidentState.READ_ONLY_COMPLETE,
            Instant.EPOCH, Instant.EPOCH, new EvidenceBundle("inc", "run", Instant.EPOCH,
            List.of(evidence)), diagnosis, List.of());
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.valueToTree(report);
        tree.remove(List.of("schemaVersion", "capabilityProfile", "model", "promptVersion", "timelineReference"));
        var diagnosisTree = (com.fasterxml.jackson.databind.node.ObjectNode) tree.path("diagnosis");
        diagnosisTree.remove(List.of("schemaVersion", "diagnosisStatus", "currentCondition",
            "evaluatedAt", "resolutionAttribution"));
        var evidenceTree = (com.fasterxml.jackson.databind.node.ObjectNode) tree.path("evidenceBundle")
            .path("evidence").get(0);
        evidenceTree.remove(List.of("schemaVersion", "collectionStatus", "validUntil", "supersedesEvidenceId"));

        IncidentReport restored = MAPPER.treeToValue(tree, IncidentReport.class);

        assertThat(restored.schemaVersion()).isEqualTo("1");
        assertThat(restored.capabilityProfile()).isEqualTo("APP_DOWN_V1");
        assertThat(restored.diagnosis().diagnosisStatus()).isEqualTo(Diagnosis.DiagnosisStatus.PROBABLE);
        assertThat(restored.evidenceBundle().evidence().getFirst().schemaVersion()).isEqualTo("1");
    }
}
