package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.clawkit.context.AnchorKind;
import com.clawkit.context.CompactionProfile;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpsCompactionHintProviderTest {

    @Test
    void mapsIncidentAndRelevantEvidenceToStableRequiredAnchors() {
        Instant detected = Instant.parse("2026-07-22T10:00:00Z");
        var incident = new IncidentInput("incident-1", detected, "orders are slow",
            "POSTGRES_DIAGNOSIS_V1", "v1");
        var fact = JsonNodeFactory.instance.objectNode().put("success", true)
            .set("data", JsonNodeFactory.instance.objectNode().put("rowCount", 2));
        var relevant = new Evidence("ev-1", "incident-1", EvidenceType.DB_LOCK_GRAPH,
            "postgres", detected, detected.plusSeconds(1), "orders", Evidence.Kind.FACT,
            fact, "run://baseline/tool/call-1", Evidence.Freshness.CURRENT,
            Evidence.Redaction.SENSITIVE_FIELDS_REMOVED);
        var optional = new Evidence("ev-2", "incident-1", EvidenceType.LOGS,
            "order-api", detected, detected.plusSeconds(1), "orders", Evidence.Kind.FACT,
            fact, "run://baseline/tool/call-2", Evidence.Freshness.CURRENT,
            Evidence.Redaction.SENSITIVE_FIELDS_REMOVED);
        var signals = new DiagnosticSignals("DB_LOCK_WAIT", "ACTIVE", true, false,
            false, true, false, 0, List.of("ev-1"));
        var provider = new OpsCompactionHintProvider(
            incident, signals, () -> List.of(relevant, optional));

        var first = provider.snapshot("run-1", 21);
        var second = provider.snapshot("run-1", 22);

        assertThat(first).isEqualTo(second);
        assertThat(first.profile()).isEqualTo(CompactionProfile.OPS_DIAGNOSIS);
        assertThat(first.anchors()).filteredOn(anchor -> anchor.required())
            .extracting(anchor -> anchor.id())
            .containsExactlyInAnyOrder("incident-incident-1", "evidence-ev-1");
        assertThat(first.anchors()).filteredOn(anchor -> anchor.id().equals("evidence-ev-2"))
            .singleElement().satisfies(anchor -> assertThat(anchor.required()).isFalse());
        assertThat(first.anchors()).filteredOn(anchor -> anchor.id().equals("hypothesis-root-cause"))
            .singleElement().satisfies(anchor -> {
                assertThat(anchor.kind()).isEqualTo(AnchorKind.OPEN_HYPOTHESIS);
                assertThat(anchor.required()).isFalse();
            });
    }
}
