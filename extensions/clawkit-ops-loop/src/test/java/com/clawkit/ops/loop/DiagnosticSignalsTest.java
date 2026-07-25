package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticSignalsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    @Test
    void identifiesCpuPressureFromIncidentPeakEvenIfCurrentMetricIsHealthy() throws Exception {
        DiagnosticSignals signals = DiagnosticSignals.extract(List.of(
            evidence("incident", EvidenceType.BUSINESS_METRIC, "business-metrics-incident", """
                {"windowSeconds":60,"requestCount":50,"errorCount":0,"p95LatencyMs":700}
                """),
            evidence("current", EvidenceType.BUSINESS_METRIC, "business-metrics-current", """
                {"windowSeconds":5,"requestCount":8,"errorCount":0,"p95LatencyMs":40}
                """),
            evidence("cpu", EvidenceType.CONTAINER_RESOURCE, "order-api", """
                {"CPUPerc":"568.2%"}
                """)));

        assertThat(signals.candidateRootCause()).isEqualTo("CPU_PRESSURE");
        assertThat(signals.candidateCurrentCondition()).isEqualTo("ACTIVE");
        assertThat(signals.maxOrderApiCpuPercent()).isEqualTo(568.2);
    }

    @Test
    void definesConnectionExhaustionAtTheApplicationPoolBoundary() throws Exception {
        DiagnosticSignals signals = DiagnosticSignals.extract(List.of(
            evidence("pool", EvidenceType.BUSINESS_METRIC, "business-metrics-incident", """
                {"windowSeconds":60,"requestCount":30,"errorCount":12,"p95LatencyMs":801,
                 "pool":{"active":6,"idle":0,"pending":8,"max":6}}
                """),
            evidence("db", EvidenceType.DB_CONNECTION_STATS, "postgres/current_database", """
                {"rows":[{"client_connections":7,"max_connections":100}]}
                """)));

        assertThat(signals.candidateRootCause()).isEqualTo("CONNECTION_EXHAUSTION");
        assertThat(signals.applicationPoolSaturated()).isTrue();
    }

    @Test
    void identifiesCompletedLockIncidentAsSelfRecoveredWhenCurrentWindowIsHealthy() throws Exception {
        DiagnosticSignals signals = DiagnosticSignals.extract(List.of(
            evidence("incident", EvidenceType.BUSINESS_METRIC, "business-metrics-incident", """
                {"windowSeconds":60,"requestCount":50,"errorCount":8,"p95LatencyMs":801}
                """),
            evidence("current", EvidenceType.BUSINESS_METRIC, "business-metrics-current", """
                {"windowSeconds":5,"requestCount":10,"errorCount":0,"p95LatencyMs":22}
                """),
            evidence("logs", EvidenceType.LOGS, "order-api", """
                {"text":"transaction acquired account row lock\\ntransaction released account row lock"}
                """)));

        assertThat(signals.candidateRootCause()).isEqualTo("DB_LOCK_WAIT");
        assertThat(signals.candidateCurrentCondition()).isEqualTo("RECOVERED");
        assertThat(signals.completedLockTransaction()).isTrue();
    }

    @Test
    void remainsInconclusiveWithoutAListedCausalSignal() throws Exception {
        DiagnosticSignals signals = DiagnosticSignals.extract(List.of(
            evidence("incident", EvidenceType.BUSINESS_METRIC, "business-metrics-incident", """
                {"windowSeconds":60,"requestCount":40,"errorCount":0,"p95LatencyMs":700}
                """),
            evidence("cpu", EvidenceType.CONTAINER_RESOURCE, "order-api", """
                {"CPUPerc":"5.0%"}
                """),
            evidence("locks", EvidenceType.DB_LOCK_GRAPH, "postgres/current_database", """
                {"rowCount":0,"rows":[]}
                """)));

        assertThat(signals.candidateRootCause()).isEqualTo("INCONCLUSIVE");
        assertThat(signals.candidateCurrentCondition()).isEqualTo("ACTIVE");
    }

    private static Evidence evidence(
        String id, EvidenceType type, String scope, String dataJson
    ) throws Exception {
        var fact = JSON.createObjectNode().put("success", true);
        fact.set("data", JSON.readTree(dataJson));
        return new Evidence(id, "incident", type, "test", NOW, NOW, scope,
            Evidence.Kind.FACT, fact, "run://test/tool/" + id,
            Evidence.Freshness.CURRENT, Evidence.Redaction.NONE, "2",
            Evidence.CollectionStatus.OBSERVED, NOW.plusSeconds(120), null);
    }
}
