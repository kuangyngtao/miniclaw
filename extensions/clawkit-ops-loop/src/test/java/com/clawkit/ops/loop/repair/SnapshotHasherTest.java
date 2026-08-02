package com.clawkit.ops.loop.repair;

import com.clawkit.ops.loop.Evidence;
import com.clawkit.ops.loop.EvidenceBundle;
import com.clawkit.ops.loop.EvidenceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SnapshotHasherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-27T12:00:00Z");

    @Test
    void shouldProduceDeterministicHash() {
        EvidenceBundle b1 = buildBundle("order-api", "running", "running", 200);
        EvidenceBundle b2 = buildBundle("order-api", "running", "running", 200);
        assertThat(SnapshotHasher.compute(b1, "order-api"))
            .isEqualTo(SnapshotHasher.compute(b2, "order-api"));
    }

    @Test
    void shouldDetectServiceStatusChange() {
        EvidenceBundle b1 = buildBundle("order-api", "running", "running", 200);
        EvidenceBundle b2 = buildBundle("order-api", "stopped", "running", 200);
        assertThat(SnapshotHasher.compute(b1, "order-api"))
            .isNotEqualTo(SnapshotHasher.compute(b2, "order-api"));
    }

    @Test
    void shouldDetectHttpProbeChange() {
        EvidenceBundle b1 = buildBundle("order-api", "running", "running", 200);
        EvidenceBundle b2 = buildBundle("order-api", "running", "running", 503);
        assertThat(SnapshotHasher.compute(b1, "order-api"))
            .isNotEqualTo(SnapshotHasher.compute(b2, "order-api"));
    }

    @Test
    void shouldRejectEmptyEvidence() {
        EvidenceBundle empty = new EvidenceBundle("inc-1", "run-1", NOW,
            List.of(buildEvidence(EvidenceType.LOGS, "container/gateway",
                "{}", Evidence.Freshness.CURRENT, Evidence.CollectionStatus.OBSERVED)));
        assertThatThrownBy(() -> SnapshotHasher.compute(empty, "order-api"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHandleEvidenceWithoutDataGracefully() {
        ObjectNode factNoData = MAPPER.createObjectNode().put("success", true);
        Evidence e = new Evidence("e-1", "inc-1", EvidenceType.SERVICE_STATUS,
            "mcp:ops", NOW, NOW, "compose/order-api", Evidence.Kind.FACT,
            factNoData, "run://r/e-1", Evidence.Freshness.CURRENT,
            Evidence.Redaction.NONE, "2", Evidence.CollectionStatus.OBSERVED,
            NOW.plusSeconds(300), null);
        EvidenceBundle b = new EvidenceBundle("inc-1", "run-1", NOW, List.of(e));
        // New SnapshotHasher uses EvidenceReader.state() which returns "UNKNOWN" for missing data
        String hash = SnapshotHasher.compute(b, "order-api");
        assertThat(hash).isNotEmpty();
    }

    @Test
    void shouldProduceNonEmpty64CharHash() {
        EvidenceBundle bundle = buildBundle("order-api", "running", "running", 200);
        String hash = SnapshotHasher.compute(bundle, "order-api");
        assertThat(hash).hasSize(64);
    }

    @Test
    void sameStateDifferentCollectionShouldProduceSameHash() {
        EvidenceBundle b1 = buildBundle("order-api", "running", "running", 200);
        // Same data, different evidenceId/runId — hash should match
        ObjectNode data = MAPPER.createObjectNode().put("State", "running").put("success", true);
        var e = buildEvidenceWithData(EvidenceType.SERVICE_STATUS, "compose/order-api", data,
            "e-different", "run-different");
        var e2 = buildEvidence(EvidenceType.CONTAINER_STATUS, "container/order-api",
            "{\"State\":\"running\",\"Running\":true}", Evidence.Freshness.CURRENT,
            Evidence.CollectionStatus.OBSERVED);
        var e3 = buildEvidence(EvidenceType.HTTP_PROBE, "endpoint/gateway-health",
            "{\"statusCode\":200,\"healthy\":true}", Evidence.Freshness.CURRENT,
            Evidence.CollectionStatus.OBSERVED);
        EvidenceBundle b2 = new EvidenceBundle("inc-1", "run-2", NOW.plusSeconds(60),
            List.of(e, e2, e3));

        assertThat(SnapshotHasher.compute(b1, "order-api"))
            .isEqualTo(SnapshotHasher.compute(b2, "order-api"));
    }

    // ── helpers ──

    private EvidenceBundle buildBundle(String svcId, String svcState,
                                        String ctrState, int httpStatus) {
        Evidence e1 = buildEvidence(EvidenceType.SERVICE_STATUS, "compose/" + svcId,
            "{\"State\":\"" + svcState + "\",\"success\":true}",
            Evidence.Freshness.CURRENT, Evidence.CollectionStatus.OBSERVED);
        Evidence e2 = buildEvidence(EvidenceType.CONTAINER_STATUS, "container/" + svcId,
            "{\"State\":\"" + ctrState + "\",\"Running\":true}",
            Evidence.Freshness.CURRENT, Evidence.CollectionStatus.OBSERVED);
        Evidence e3 = buildEvidence(EvidenceType.HTTP_PROBE, "endpoint/gateway-health",
            "{\"statusCode\":" + httpStatus + ",\"healthy\":true}",
            Evidence.Freshness.CURRENT, Evidence.CollectionStatus.OBSERVED);
        return new EvidenceBundle("inc-1", "run-1", NOW, List.of(e1, e2, e3));
    }

    private Evidence buildEvidence(EvidenceType type, String scope, String dataJson,
                                    Evidence.Freshness freshness, Evidence.CollectionStatus status) {
        ObjectNode fact;
        try {
            ObjectNode parsed = (ObjectNode) MAPPER.readTree(dataJson);
            fact = MAPPER.createObjectNode();
            fact.put("success", true);
            fact.set("data", parsed);
        } catch (Exception e) {
            fact = MAPPER.createObjectNode().put("success", false);
        }
        return new Evidence("e-" + type.name() + "-" + scope.hashCode(), "inc-1", type,
            "mcp:ops", NOW, NOW, scope, Evidence.Kind.FACT, fact,
            "run://run-1/e-1", freshness, Evidence.Redaction.NONE, "2", status,
            NOW.plusSeconds(300), null);
    }

    private Evidence buildEvidenceWithData(EvidenceType type, String scope, ObjectNode data,
                                            String evidenceId, String runId) {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", true);
        fact.set("data", data);
        return new Evidence(evidenceId, "inc-1", type,
            "mcp:ops", NOW, NOW, scope, Evidence.Kind.FACT, fact,
            "run://" + runId + "/" + evidenceId, Evidence.Freshness.CURRENT,
            Evidence.Redaction.NONE, "2", Evidence.CollectionStatus.OBSERVED,
            NOW.plusSeconds(300), null);
    }
}
