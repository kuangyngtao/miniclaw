package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekDiagnosisGateTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-07-26T00:00:00Z"), java.time.ZoneOffset.UTC);

    @Test void incompleteDiscoveryDoesNotCallProvider() {
        var counter = new AtomicInteger();
        var gate = new DeepSeekDiagnosisGate(r -> { counter.incrementAndGet(); return "{}"; },
            "test", CLOCK);
        var result = incompleteResult();
        gate.diagnose(result, "test symptom");
        assertThat(counter.get()).isEqualTo(0);
    }

    @Test void transportFailedDoesNotCallProvider() {
        var counter = new AtomicInteger();
        var gate = new DeepSeekDiagnosisGate(r -> { counter.incrementAndGet(); return "{}"; },
            "test", CLOCK);
        var result = transportFailedResult();
        gate.diagnose(result, "test symptom");
        assertThat(counter.get()).isEqualTo(0);
    }

    @Test void completeDiscoveryCallsProviderOnce() {
        var counter = new AtomicInteger();
        var gate = new DeepSeekDiagnosisGate(r -> { counter.incrementAndGet(); return emptyDiagnosis(); },
            "test", CLOCK);
        var result = completeResult();
        gate.diagnose(result, "test symptom");
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test void emptyResponseRetriesOnceThenInconclusive() {
        var counter = new AtomicInteger();
        var gate = new DeepSeekDiagnosisGate(r -> {
            counter.incrementAndGet();
            if (counter.get() == 1) throw new IOException("empty");
            return emptyDiagnosis(); // second call succeeds
        }, "test", CLOCK);
        var result = completeResult();
        Diagnosis d = gate.diagnose(result, "test");
        assertThat(counter.get()).isEqualTo(2); // retried once
        assertThat(d.rootCauseCode()).isEqualTo("INCONCLUSIVE"); // empty JSON → INCONCLUSIVE
    }

    @Test void secondFailureReturnsInconclusive() {
        var gate = new DeepSeekDiagnosisGate(r -> { throw new IOException("fail"); },
            "test", CLOCK);
        var result = completeResult();
        Diagnosis d = gate.diagnose(result, "test");
        assertThat(d.rootCauseCode()).isEqualTo("INCONCLUSIVE");
        assertThat(d.confidence()).isEqualTo(0.0);
    }

    @Test void fabricatedEvidenceIdIsRejected() {
        var gate = new DeepSeekDiagnosisGate(r ->
            "{\"rootCauseCode\":\"DEMO_API_STOPPED\",\"confidence\":0.9,"
            + "\"supportingEvidence\":[\"e-fake\"],\"contradictingEvidence\":[],"
            + "\"alternatives\":[],\"missingEvidence\":[],"
            + "\"recommendedActionCode\":\"ESCALATE\",\"claimedResolved\":false}",
            "test", CLOCK);
        var result = completeResult();
        Diagnosis d = gate.diagnose(result, "test");
        assertThat(d.rootCauseCode()).isEqualTo("INCONCLUSIVE");
    }

    @Test void claimedResolvedTrueIsRejected() {
        var gate = new DeepSeekDiagnosisGate(r ->
            "{\"rootCauseCode\":\"DEMO_API_STOPPED\",\"confidence\":0.9,"
            + "\"supportingEvidence\":[],\"contradictingEvidence\":[],"
            + "\"alternatives\":[],\"missingEvidence\":[],"
            + "\"recommendedActionCode\":\"ESCALATE\",\"claimedResolved\":true}",
            "test", CLOCK);
        var result = completeResult();
        Diagnosis d = gate.diagnose(result, "test");
        assertThat(d.rootCauseCode()).isEqualTo("INCONCLUSIVE");
    }

    @Test void hostAndKeyNotInPrompt() {
        var sb = new StringBuilder();
        var gate = new DeepSeekDiagnosisGate(r -> { sb.append(r); return emptyDiagnosis(); },
            "test", CLOCK);
        var result = completeResult();
        gate.diagnose(result, "test");
        String prompt = sb.toString();
        assertThat(prompt).doesNotContain("122.51.51.118");
        assertThat(prompt).doesNotContain("id_ed25519");
        assertThat(prompt).doesNotContain("CLAWKIT_OPS");
        assertThat(prompt).doesNotContain("CLAWKIT_REMOTE");
        assertThat(prompt).doesNotContain("ssh -i");
    }

    // ── Helpers ──

    private DiscoveryResult completeResult() {
        return new DiscoveryResult("inc-1", "run-1", "REMOTE_APP_DOWN_V1",
            evidenceBundle(6), DiscoveryStatus.COMPLETE, 6, 6, CLOCK.instant());
    }

    private DiscoveryResult incompleteResult() {
        return new DiscoveryResult("inc-1", "run-1", "REMOTE_APP_DOWN_V1",
            evidenceBundle(2), DiscoveryStatus.INCOMPLETE, 2, 6, CLOCK.instant());
    }

    private DiscoveryResult transportFailedResult() {
        return new DiscoveryResult("inc-1", "run-1", "REMOTE_APP_DOWN_V1",
            evidenceBundle(0), DiscoveryStatus.TRANSPORT_FAILED, 3, 6, CLOCK.instant());
    }

    private EvidenceBundle evidenceBundle(int count) {
        List<Evidence> list = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            ObjectNode fact = MAPPER.createObjectNode();
            fact.put("success", true);
            fact.putObject("data").put("State", "running");
            list.add(new Evidence("e-" + (i + 1), "inc-1", EvidenceType.SERVICE_STATUS,
                "mcp:ops/svc", CLOCK.instant(), CLOCK.instant(),
                "scope" + i, Evidence.Kind.FACT, fact,
                "run://r1/tool/e-" + (i + 1),
                Evidence.Freshness.CURRENT, Evidence.Redaction.NONE,
                "2", Evidence.CollectionStatus.OBSERVED,
                CLOCK.instant().plusSeconds(120), null));
        }
        return new EvidenceBundle("inc-1", "run-1", CLOCK.instant(), list.isEmpty()
            ? List.of(new Evidence("e-0", "inc-1", EvidenceType.SERVICE_STATUS,
                "mcp:ops/svc", CLOCK.instant(), CLOCK.instant(), "s",
                Evidence.Kind.FACT, MAPPER.createObjectNode(),
                "run://r1/tool/e-0", Evidence.Freshness.STALE, Evidence.Redaction.NONE))
            : list);
    }

    private static String emptyDiagnosis() {
        return "```json\n{\"rootCauseCode\":\"INCONCLUSIVE\",\"confidence\":0.0,"
            + "\"supportingEvidence\":[],\"contradictingEvidence\":[],"
            + "\"alternatives\":[],\"missingEvidence\":[],"
            + "\"recommendedActionCode\":\"ESCALATE\",\"claimedResolved\":false}\n```";
    }
}
