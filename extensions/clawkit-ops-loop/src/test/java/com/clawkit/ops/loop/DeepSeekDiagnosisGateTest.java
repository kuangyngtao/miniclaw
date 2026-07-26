package com.clawkit.ops.loop;

import com.clawkit.provider.LLMProvider;
import com.clawkit.provider.ModelRequest;
import com.clawkit.provider.ModelResponse;
import com.clawkit.provider.TokenUsage;
import com.clawkit.provider.ProviderResponseMetadata;
import com.clawkit.provider.FinishReason;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

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

    // ── Stub LLMProvider that returns a pre-canned text response ──

    private static LLMProvider stubProvider(String responseText) {
        return new LLMProvider() {
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> tools) {
                return Message.assistant(responseText);
            }
        };
    }

    private static LLMProvider throwingProvider() {
        return new LLMProvider() {
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> tools) {
                throw new com.clawkit.provider.LLMException("simulated network error",
                    new com.clawkit.provider.ProviderError.Network("simulated"));
            }
        };
    }

    private static LLMProvider countingProvider(AtomicInteger counter, String response) {
        return new LLMProvider() {
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> tools) {
                counter.incrementAndGet();
                return Message.assistant(response);
            }
        };
    }

    // ── Tests ──

    @Test void incompleteDiscoveryDoesNotCallProvider() {
        var counter = new AtomicInteger();
        var gate = new DeepSeekDiagnosisGate(countingProvider(counter, "{}"), "test", CLOCK);
        var result = incompleteResult();
        gate.diagnose(result, "test symptom");
        assertThat(counter.get()).isEqualTo(0);
    }

    @Test void transportFailedDoesNotCallProvider() {
        var counter = new AtomicInteger();
        var gate = new DeepSeekDiagnosisGate(countingProvider(counter, "{}"), "test", CLOCK);
        var result = transportFailedResult();
        gate.diagnose(result, "test symptom");
        assertThat(counter.get()).isEqualTo(0);
    }

    @Test void completeDiscoveryCallsProviderOnce() {
        var counter = new AtomicInteger();
        var gate = new DeepSeekDiagnosisGate(
            countingProvider(counter, emptyDiagnosisText()), "test", CLOCK);
        var result = completeResult();
        gate.diagnose(result, "test symptom");
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test void emptyResponseRetriesOnceThenInconclusive() {
        var counter = new AtomicInteger();
        var gate = new DeepSeekDiagnosisGate(new LLMProvider() {
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> tools) {
                int call = counter.incrementAndGet();
                if (call == 1) return Message.assistant(""); // empty
                return Message.assistant(emptyDiagnosisText());
            }
        }, "test", CLOCK);
        var result = completeResult();
        Diagnosis d = gate.diagnose(result, "test");
        assertThat(counter.get()).isEqualTo(2); // retried once
        assertThat(d.rootCauseCode()).isEqualTo("INCONCLUSIVE");
    }

    @Test void providerNetworkFailureReturnsInconclusive() {
        var gate = new DeepSeekDiagnosisGate(throwingProvider(), "test", CLOCK);
        var result = completeResult();
        Diagnosis d = gate.diagnose(result, "test");
        assertThat(d.rootCauseCode()).isEqualTo("INCONCLUSIVE");
        assertThat(d.confidence()).isEqualTo(0.0);
    }

    @Test void fabricatedEvidenceIdIsRejected() {
        var gate = new DeepSeekDiagnosisGate(
            stubProvider("{\"rootCauseCode\":\"DEMO_API_STOPPED\",\"confidence\":0.9,"
                + "\"diagnosisStatus\":\"CONFIRMED\","
                + "\"supportingEvidence\":[\"e-fake\"],\"contradictingEvidence\":[],"
                + "\"alternatives\":[],\"missingEvidence\":[],"
                + "\"recommendedActionCode\":\"ESCALATE\",\"claimedResolved\":false}"),
            "test", CLOCK);
        var result = completeResult();
        Diagnosis d = gate.diagnose(result, "test");
        assertThat(d.rootCauseCode()).isEqualTo("INCONCLUSIVE");
    }

    @Test void claimedResolvedTrueIsRejected() {
        var gate = new DeepSeekDiagnosisGate(
            stubProvider("{\"rootCauseCode\":\"DEMO_API_STOPPED\",\"confidence\":0.9,"
                + "\"diagnosisStatus\":\"CONFIRMED\","
                + "\"supportingEvidence\":[],\"contradictingEvidence\":[],"
                + "\"alternatives\":[],\"missingEvidence\":[],"
                + "\"recommendedActionCode\":\"ESCALATE\",\"claimedResolved\":true}"),
            "test", CLOCK);
        var result = completeResult();
        Diagnosis d = gate.diagnose(result, "test");
        assertThat(d.rootCauseCode()).isEqualTo("INCONCLUSIVE");
    }

    @Test void hostAndKeyNotInPrompt() {
        var sb = new StringBuilder();
        var gate = new DeepSeekDiagnosisGate(new LLMProvider() {
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> tools) {
                for (Message m : messages) {
                    if (m.content() != null) sb.append(m.content());
                }
                return Message.assistant(emptyDiagnosisText());
            }
        }, "test", CLOCK);
        var result = completeResult();
        gate.diagnose(result, "test");
        String prompt = sb.toString();
        assertThat(prompt).doesNotContain("122.51.51.118");
        assertThat(prompt).doesNotContain("id_ed25519");
        assertThat(prompt).doesNotContain("CLAWKIT_OPS");
        assertThat(prompt).doesNotContain("CLAWKIT_REMOTE");
        assertThat(prompt).doesNotContain("ssh -i");
    }

    @Test void staleEvidenceDoesNotCallProvider() {
        var counter = new AtomicInteger();
        var gate = new DeepSeekDiagnosisGate(countingProvider(counter, "{}"), "test", CLOCK);
        var result = staleEvidenceResult();
        gate.diagnose(result, "test symptom");
        assertThat(counter.get()).isEqualTo(0);
    }

    @Test void deadlinePropagatesToModelRequest() {
        var capturedControl = new java.util.concurrent.atomic.AtomicReference
            <com.clawkit.tools.control.ExecutionControl>();
        var gate = new DeepSeekDiagnosisGate(new LLMProvider() {
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> tools) {
                return Message.assistant(emptyDiagnosisText());
            }
            @Override
            public ModelResponse generate(ModelRequest request) {
                capturedControl.set(request.control());
                return new ModelResponse(emptyDiagnosisText(), List.of(),
                    FinishReason.STOP, TokenUsage.EMPTY,
                    new ProviderResponseMetadata("test", "id1", 0));
            }
        }, "test", CLOCK);
        var result = completeResult();
        Duration deadline = Duration.ofSeconds(30);
        gate.diagnose(result, "test symptom", deadline);
        assertThat(capturedControl.get()).isNotNull();
        assertThat(capturedControl.get().deadline()).isPresent();
    }

    @Test void diagnosisContainsValidEvidenceIds() {
        var gate = new DeepSeekDiagnosisGate(
            stubProvider("{\"rootCauseCode\":\"DEMO_API_STOPPED\",\"confidence\":0.9,"
                + "\"diagnosisStatus\":\"CONFIRMED\","
                + "\"supportingEvidence\":[\"e-1\",\"e-3\"],"
                + "\"contradictingEvidence\":[\"e-5\"],"
                + "\"alternatives\":[],\"missingEvidence\":[],"
                + "\"recommendedActionCode\":\"ESCALATE\",\"claimedResolved\":false}"),
            "test", CLOCK);
        var result = completeResult();
        Diagnosis d = gate.diagnose(result, "test");
        assertThat(d.rootCauseCode()).isEqualTo("DEMO_API_STOPPED");
        assertThat(d.supportingEvidence()).containsExactly("e-1", "e-3");
        assertThat(d.contradictingEvidence()).containsExactly("e-5");
    }

    @Test void invalidJsonReturnsInconclusive() {
        var gate = new DeepSeekDiagnosisGate(
            stubProvider("not json at all"), "test", CLOCK);
        var result = completeResult();
        Diagnosis d = gate.diagnose(result, "test");
        assertThat(d.rootCauseCode()).isEqualTo("INCONCLUSIVE");
    }

    // ── Helpers ──

    private DiscoveryResult completeResult() {
        return new DiscoveryResult("inc-1", "run-1", "REMOTE_APP_DOWN_V1",
            evidenceBundle(6, CLOCK.instant().plusSeconds(120)),
            DiscoveryStatus.COMPLETE, 6, 6, CLOCK.instant());
    }

    private DiscoveryResult incompleteResult() {
        return new DiscoveryResult("inc-1", "run-1", "REMOTE_APP_DOWN_V1",
            evidenceBundle(2, CLOCK.instant().plusSeconds(120)),
            DiscoveryStatus.INCOMPLETE, 2, 6, CLOCK.instant());
    }

    private DiscoveryResult transportFailedResult() {
        return new DiscoveryResult("inc-1", "run-1", "REMOTE_APP_DOWN_V1",
            evidenceBundle(3, null),
            DiscoveryStatus.TRANSPORT_FAILED, 3, 6, CLOCK.instant());
    }

    private DiscoveryResult staleEvidenceResult() {
        // All evidence validUntil is 12h before clock time → stale
        Instant pastObserved = CLOCK.instant().minusSeconds(86400); // 24h ago
        Instant pastValidUntil = CLOCK.instant().minusSeconds(43200); // 12h ago
        return new DiscoveryResult("inc-1", "run-1", "REMOTE_APP_DOWN_V1",
            evidenceBundle(6, pastObserved, pastValidUntil),
            DiscoveryStatus.COMPLETE, 6, 6, CLOCK.instant());
    }

    private EvidenceBundle evidenceBundle(int count, Instant validUntil) {
        return evidenceBundle(count, CLOCK.instant(), validUntil);
    }

    private EvidenceBundle evidenceBundle(int count, Instant observedAt, Instant validUntil) {
        List<Evidence> list = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            ObjectNode fact = MAPPER.createObjectNode();
            fact.put("success", true);
            fact.putObject("data").put("State", "running");
            list.add(new Evidence("e-" + (i + 1), "inc-1", EvidenceType.SERVICE_STATUS,
                "mcp:ops/svc", observedAt, observedAt,
                "scope" + i, Evidence.Kind.FACT, fact,
                "run://r1/tool/e-" + (i + 1),
                Evidence.Freshness.CURRENT, Evidence.Redaction.NONE,
                "2", Evidence.CollectionStatus.OBSERVED,
                validUntil, null));
        }
        if (list.isEmpty()) {
            list.add(new Evidence("e-0", "inc-1", EvidenceType.SERVICE_STATUS,
                "mcp:ops/svc", CLOCK.instant(), CLOCK.instant(), "s",
                Evidence.Kind.FACT, MAPPER.createObjectNode(),
                "run://r1/tool/e-0", Evidence.Freshness.STALE, Evidence.Redaction.NONE,
                "2", Evidence.CollectionStatus.COLLECTION_FAILED, null, null));
        }
        return new EvidenceBundle("inc-1", "run-1", CLOCK.instant(), list);
    }

    private static String emptyDiagnosisText() {
        return "```json\n{\"rootCauseCode\":\"INCONCLUSIVE\",\"confidence\":0.0,"
            + "\"diagnosisStatus\":\"INCONCLUSIVE\","
            + "\"supportingEvidence\":[],\"contradictingEvidence\":[],"
            + "\"alternatives\":[],\"missingEvidence\":[],"
            + "\"recommendedActionCode\":\"ESCALATE\",\"claimedResolved\":false}\n```";
    }
}
