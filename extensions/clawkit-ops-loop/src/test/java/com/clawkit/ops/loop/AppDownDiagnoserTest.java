package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppDownDiagnoserTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void diagnosesStoppedDemoApiFromIndependentCurrentEvidence() {
        EvidenceBundle bundle = bundle(List.of(
            evidence("e-probe", EvidenceType.HTTP_PROBE,
                "endpoint/gateway-health", fact("statusCode", 502)),
            evidence("e-demo", EvidenceType.CONTAINER_STATUS,
                "container/demo-api", containerFact(false)),
            evidence("e-gateway", EvidenceType.CONTAINER_STATUS,
                "container/gateway", containerFact(true)),
            evidence("e-logs", EvidenceType.LOGS,
                "container/gateway", fact("text", "connect() failed: Connection refused"))
        ));

        Diagnosis diagnosis = new AppDownDiagnoser().diagnose(bundle);

        assertThat(diagnosis.rootCauseCode()).isEqualTo("DEMO_API_CONTAINER_STOPPED");
        assertThat(diagnosis.supportingEvidence())
            .contains("e-probe", "e-demo", "e-gateway", "e-logs");
        assertThat(diagnosis.claimedResolved()).isFalse();
    }

    @Test
    void remainsInconclusiveWhenDemoApiIsStillRunning() {
        EvidenceBundle bundle = bundle(List.of(
            evidence("e-probe", EvidenceType.HTTP_PROBE,
                "endpoint/gateway-health", fact("statusCode", 502)),
            evidence("e-demo", EvidenceType.CONTAINER_STATUS,
                "container/demo-api", containerFact(true)),
            evidence("e-gateway", EvidenceType.CONTAINER_STATUS,
                "container/gateway", containerFact(true)),
            evidence("e-logs", EvidenceType.LOGS,
                "container/gateway", fact("text", "no matching log"))
        ));

        Diagnosis diagnosis = new AppDownDiagnoser().diagnose(bundle);

        assertThat(diagnosis.rootCauseCode()).isEqualTo("INCONCLUSIVE");
        assertThat(diagnosis.contradictingEvidence()).contains("e-demo");
    }

    private static EvidenceBundle bundle(List<Evidence> evidence) {
        return new EvidenceBundle("inc-1", "run-1", Instant.EPOCH, evidence);
    }

    private static Evidence evidence(
        String id, EvidenceType type, String scope, ObjectNode data
    ) {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", true);
        fact.set("data", data);
        return new Evidence(id, "inc-1", type, "mcp:ops/test",
            Instant.EPOCH, Instant.EPOCH, scope, Evidence.Kind.FACT, fact,
            "run://run-1/tool/" + id, Evidence.Freshness.CURRENT,
            Evidence.Redaction.NONE);
    }

    private static ObjectNode fact(String key, int value) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put(key, value);
        return data;
    }

    private static ObjectNode fact(String key, String value) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put(key, value);
        return data;
    }

    private static ObjectNode containerFact(boolean running) {
        ObjectNode data = MAPPER.createObjectNode();
        data.putObject("state").put("Running", running);
        return data;
    }
}
