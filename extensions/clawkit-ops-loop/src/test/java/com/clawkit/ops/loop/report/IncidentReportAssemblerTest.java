package com.clawkit.ops.loop.report;

import com.clawkit.ops.loop.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentReportAssemblerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    @Test void assemblesReportFromCompleteResult() {
        var result = completeResult();
        var report = IncidentReportAssembler.assemble(result);

        assertThat(report.schemaVersion()).isEqualTo("1");
        assertThat(report.dataLabel()).isEqualTo("SYNTHETIC_BUSINESS_DATA");
        assertThat(report.incidentId()).isEqualTo("inc-test-1");
        assertThat(report.rootCauseCode()).isEqualTo("DEMO_API_STOPPED");
        assertThat(report.status()).isEqualTo(HumanIncidentReport.IncidentStatus.ACTIVE);
        assertThat(report.diagnosisConfidence())
            .isEqualTo(HumanIncidentReport.DiagnosisConfidence.CONFIRMED);
        assertThat(report.contentHash()).isNotNull().isNotEmpty();
        assertThat(report.claimedResolved()).isFalse();
    }

    @Test void reportContainsSupportingEvidence() {
        var report = IncidentReportAssembler.assemble(completeResult());
        assertThat(report.supportingEvidence()).hasSize(2);
        assertThat(report.supportingEvidence().get(0).evidenceId()).isEqualTo("e-1");
    }

    @Test void reportContainsTimeline() {
        var report = IncidentReportAssembler.assemble(completeResult());
        assertThat(report.timeline()).isNotEmpty();
        assertThat(report.timeline().get(0).event()).contains("Discovery");
    }

    @Test void failedEvidenceIsReported() {
        var discovery = new DiscoveryResult("inc-1", "run-1", "REMOTE_APP_DOWN_V1",
            failedBundle(), DiscoveryStatus.COMPLETE, 5, 6, NOW);
        var diagnosis = new Diagnosis("INCONCLUSIVE", 0.3,
            List.of(), List.of(), List.of(), List.of("e-6"), "ESCALATE", false);
        var result = new RemoteIncidentResult(discovery, diagnosis, true, null, NOW);

        var report = IncidentReportAssembler.assemble(result);
        assertThat(report.failedOrStaleEvidence()).isNotEmpty();
    }

    @Test void inconclusiveDiagnosisRequiresEscalation() {
        var discovery = new DiscoveryResult("inc-1", "run-1", "REMOTE_APP_DOWN_V1",
            minimalBundle("inc-1", "run-1"), DiscoveryStatus.COMPLETE, 1, 1, NOW);
        var diagnosis = new Diagnosis("INCONCLUSIVE", 0.0,
            List.of(), List.of(), List.of(), List.of(), "ESCALATE", false);
        var result = new RemoteIncidentResult(discovery, diagnosis, false,
            "DISCOVERY_INCOMPLETE", NOW);

        var report = IncidentReportAssembler.assemble(result);
        assertThat(report.requiresHumanEscalation()).isTrue();
        assertThat(report.diagnosisConfidence())
            .isEqualTo(HumanIncidentReport.DiagnosisConfidence.INCONCLUSIVE);
    }

    @Test void contentHashIsStable() {
        var r1 = IncidentReportAssembler.assemble(completeResult());
        var r2 = IncidentReportAssembler.assemble(completeResult());
        assertThat(r1.contentHash()).isEqualTo(r2.contentHash());
    }

    @Test void markdownRenderDoesNotContainSensitiveData() {
        var report = IncidentReportAssembler.assemble(completeResult());
        String md = MarkdownIncidentRenderer.render(report);
        assertThat(md).doesNotContain("122.51.51.118");
        assertThat(md).doesNotContain("id_ed25519");
        assertThat(md).doesNotContain("fixture-control-only");
        assertThat(md).doesNotContain("CLAWKIT_API_KEY");
    }

    @Test void jsonRenderIsValid() {
        var report = IncidentReportAssembler.assemble(completeResult());
        String json = JsonIncidentRenderer.render(report);
        assertThat(json).contains("\"incidentId\"");
        assertThat(json).contains("\"SYNTHETIC_BUSINESS_DATA\"");
        // Must not contain raw host/credentials
        assertThat(json).doesNotContain("122.51.51.118");
    }

    @Test void feishuSummaryContainsKeyInfo() {
        var report = IncidentReportAssembler.assemble(completeResult());
        String summary = FeishuSummaryRenderer.render(report);
        assertThat(summary).contains("SYNTHETIC_BUSINESS_DATA");
        assertThat(summary).contains("inc-test-1");
        assertThat(summary).contains("DEMO_API_STOPPED");
        // Must NOT contain sensitive data
        assertThat(summary).doesNotContain("fixture-control-only");
        assertThat(summary).doesNotContain("CLAWKIT_API_KEY");
    }

    @Test void scopeIsSanitized() {
        String sanitized = IncidentReportAssembler.sanitizeScope(
            "http://192.168.1.100:5432/db");
        assertThat(sanitized).doesNotContain("192.168");
        assertThat(sanitized).doesNotContain("5432");
    }

    @Test void factSummaryIsHumanReadable() {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", true);
        fact.putObject("data").put("State", "running");
        String summary = IncidentReportAssembler.summarizeFact(fact);
        assertThat(summary).contains("ok").contains("running");
    }

    // ── Helpers ──

    private RemoteIncidentResult completeResult() {
        ObjectNode fact1 = MAPPER.createObjectNode();
        fact1.put("success", true);
        fact1.putObject("data").put("State", "running");
        Evidence e1 = new Evidence("e-1", "inc-test-1", EvidenceType.SERVICE_STATUS,
            "mcp:ops/svc", NOW, NOW, "svc", Evidence.Kind.FACT, fact1,
            "run://r1/e-1", Evidence.Freshness.CURRENT, Evidence.Redaction.NONE,
            "2", Evidence.CollectionStatus.OBSERVED, NOW.plusSeconds(120), null);

        ObjectNode fact2 = MAPPER.createObjectNode();
        fact2.put("success", true);
        fact2.putObject("data").put("State", "running");
        Evidence e2 = new Evidence("e-2", "inc-test-1", EvidenceType.HTTP_PROBE,
            "mcp:ops/http", NOW, NOW, "http", Evidence.Kind.FACT, fact2,
            "run://r1/e-2", Evidence.Freshness.CURRENT, Evidence.Redaction.NONE,
            "2", Evidence.CollectionStatus.OBSERVED, NOW.plusSeconds(120), null);

        var bundle = new EvidenceBundle("inc-test-1", "run-1", NOW, List.of(e1, e2));
        var discovery = new DiscoveryResult("inc-test-1", "run-1",
            "REMOTE_APP_DOWN_V1", bundle, DiscoveryStatus.COMPLETE, 2, 2, NOW);
        var diagnosis = new Diagnosis("DEMO_API_STOPPED", 0.95,
            List.of("e-1", "e-2"), List.of(), List.of(), List.of(),
            "RESTART_SERVICE", false, "1",
            Diagnosis.DiagnosisStatus.CONFIRMED,
            Diagnosis.CurrentCondition.ACTIVE,
            NOW, Diagnosis.ResolutionAttribution.NONE);
        return new RemoteIncidentResult(discovery, diagnosis, true, null, NOW);
    }

    private EvidenceBundle failedBundle() {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", false);
        fact.put("error", "Connection refused");
        Evidence e = new Evidence("e-6", "inc-1", EvidenceType.SERVICE_STATUS,
            "mcp:ops/svc", NOW, NOW, "svc", Evidence.Kind.FACT, fact,
            "run://r1/e-6", Evidence.Freshness.STALE, Evidence.Redaction.NONE,
            "2", Evidence.CollectionStatus.COLLECTION_FAILED, null, null);
        return new EvidenceBundle("inc-1", "run-1", NOW, List.of(e));
    }

    private EvidenceBundle minimalBundle(String incId, String runId) {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", true);
        Evidence e = new Evidence("e-1", incId, EvidenceType.SERVICE_STATUS,
            "mcp:ops/svc", NOW, NOW, "scope", Evidence.Kind.FACT, fact,
            "run://r1/e-1", Evidence.Freshness.CURRENT, Evidence.Redaction.NONE,
            "2", Evidence.CollectionStatus.OBSERVED, NOW.plusSeconds(120), null);
        return new EvidenceBundle(incId, runId, NOW, List.of(e));
    }
}
