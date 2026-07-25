package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PostgresCaseEvaluatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void rejectsExpiredSupportingEvidenceEvenWhenRootCauseMatches() {
        Evidence stale = evidence("e-lock", EvidenceType.DB_LOCK_GRAPH, NOW.minusSeconds(60), NOW.minusSeconds(30));
        IncidentReport report = report(new Diagnosis("DB_LOCK_WAIT", .9, List.of("e-lock"),
            List.of(), List.of("CPU_PRESSURE"), List.of(), "ESCALATE", false,
            "2", Diagnosis.DiagnosisStatus.CONFIRMED, Diagnosis.CurrentCondition.ACTIVE,
            NOW, Diagnosis.ResolutionAttribution.NONE), List.of(stale));
        PostgresCaseControl truth = new PostgresCaseControl("secret", PostgresCaseControl.CaseType.DB_LOCK_WAIT,
            "DB_LOCK_WAIT", Set.of(EvidenceType.DB_LOCK_GRAPH), Set.of("bash"));

        EvaluationResult result = new PostgresCaseEvaluator(CLOCK).evaluate(truth, report, List.of("db_lock_graph"));

        assertThat(result.passed()).isFalse();
        assertThat(result.vetoes()).contains("STALE_OR_FAILED_SUPPORTING_EVIDENCE");
    }

    @Test
    void acceptsUnknownOnlyAsExplicitInconclusive() {
        Evidence metric = evidence("e-m", EvidenceType.BUSINESS_METRIC, NOW, NOW.plusSeconds(30));
        Diagnosis diagnosis = new Diagnosis("INCONCLUSIVE", .2, List.of("e-m"), List.of(),
            List.of(), List.of("cause-specific signal"), "ESCALATE", false, "2",
            Diagnosis.DiagnosisStatus.INCONCLUSIVE, Diagnosis.CurrentCondition.UNKNOWN,
            NOW, Diagnosis.ResolutionAttribution.NONE);
        IncidentReport report = report(diagnosis, List.of(metric));
        PostgresCaseControl truth = new PostgresCaseControl("secret", PostgresCaseControl.CaseType.UNKNOWN,
            "INCONCLUSIVE", Set.of(EvidenceType.BUSINESS_METRIC), Set.of("bash"));

        assertThat(new PostgresCaseEvaluator(CLOCK).evaluate(truth, report,
            List.of("business_metrics")).passed()).isTrue();
    }

    @Test
    void rejectsCaseTypeLeakedThroughIncidentId() {
        Evidence lock = evidence("ops0b-db_lock_wait-1", "e-lock",
            EvidenceType.DB_LOCK_GRAPH, NOW, NOW.plusSeconds(30));
        Diagnosis diagnosis = new Diagnosis("DB_LOCK_WAIT", .9, List.of("e-lock"), List.of(),
            List.of("CPU_PRESSURE"), List.of(), "ESCALATE", false, "2",
            Diagnosis.DiagnosisStatus.CONFIRMED, Diagnosis.CurrentCondition.ACTIVE,
            NOW, Diagnosis.ResolutionAttribution.NONE);
        IncidentReport report = new IncidentReport("ops0b-db_lock_wait-1", "run",
            IncidentState.READ_ONLY_COMPLETE, NOW, NOW,
            new EvidenceBundle("ops0b-db_lock_wait-1", "run", NOW, List.of(lock)),
            diagnosis, List.of(), "2", "POSTGRES_DIAGNOSIS_V1", "test-model", "v1",
            "incident-events.jsonl");
        PostgresCaseControl truth = new PostgresCaseControl("secret",
            PostgresCaseControl.CaseType.DB_LOCK_WAIT, "DB_LOCK_WAIT",
            Set.of(EvidenceType.DB_LOCK_GRAPH), Set.of("bash"));

        EvaluationResult result = new PostgresCaseEvaluator(CLOCK).evaluate(
            truth, report, List.of("db_lock_graph"));

        assertThat(result.passed()).isFalse();
        assertThat(result.assertions()).containsEntry("noGroundTruthLeak", false);
    }

    private static Evidence evidence(String id, EvidenceType type, Instant observed, Instant validUntil) {
        return evidence("inc", id, type, observed, validUntil);
    }

    private static Evidence evidence(
        String incidentId, String id, EvidenceType type, Instant observed, Instant validUntil
    ) {
        var fact = MAPPER.createObjectNode().put("success", true);
        return new Evidence(id, incidentId, type, "mcp:ops/test", observed, observed,
            "scope", Evidence.Kind.FACT, fact, "run://run/tool/one",
            Evidence.Freshness.CURRENT, Evidence.Redaction.SENSITIVE_FIELDS_REMOVED,
            "2", Evidence.CollectionStatus.OBSERVED, validUntil, null);
    }

    private static IncidentReport report(Diagnosis diagnosis, List<Evidence> evidence) {
        return new IncidentReport("inc", "run", IncidentState.READ_ONLY_COMPLETE, NOW, NOW,
            new EvidenceBundle("inc", "run", NOW, evidence), diagnosis, List.of(),
            "2", "POSTGRES_DIAGNOSIS_V1", "test-model", "v1", "incident-events.jsonl");
    }
}
