package com.clawkit.observability;

import com.clawkit.tools.ToolRiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunEventCodecTest {

    private RunEventCodec codec;
    private static final String RUN_ID = "run-001";
    private static final Instant NOW = Instant.parse("2026-07-11T10:00:00Z");

    @BeforeEach
    void setUp() {
        codec = new RunEventCodec(new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    @Test
    void shouldEncodeAndDecodeRunStartedRoundTrip() throws Exception {
        var payload = new RunStartedPayload("Do a thing", "/tmp/work", "gpt-4",
            "ASK", "none", "ReAct");
        var envelope = codec.encode(payload, RUN_ID, null, null, 1, NOW);

        String json = codec.serialize(envelope);
        var decoded = codec.deserialize(json);

        assertThat(decoded.schemaVersion()).isEqualTo(1);
        assertThat(decoded.eventType()).isEqualTo(RunEventType.RUN_STARTED);
        assertThat(decoded.runId()).isEqualTo(RUN_ID);
        assertThat(decoded.sequence()).isEqualTo(1);
        assertThat(decoded.parentRunId()).isNull();
        assertThat(decoded.turnNumber()).isNull();
        assertThat(decoded.payload()).isInstanceOf(RunStartedPayload.class);

        var decodedPayload = (RunStartedPayload) decoded.payload();
        assertThat(decodedPayload.taskSummary()).isEqualTo("Do a thing");
        assertThat(decodedPayload.workDir()).isEqualTo("/tmp/work");
        assertThat(decodedPayload.model()).isEqualTo("gpt-4");
        assertThat(decodedPayload.permissionMode()).isEqualTo("ASK");
    }

    @Test
    void shouldEncodeAndDecodeRunCompletedRoundTrip() throws Exception {
        var payload = new RunCompletedPayload(RunStatus.COMPLETED, null, null);
        var envelope = codec.encode(payload, RUN_ID, null, null, 2, NOW);

        String json = codec.serialize(envelope);
        var decoded = codec.deserialize(json);

        assertThat(decoded.eventType()).isEqualTo(RunEventType.RUN_COMPLETED);
        assertThat(decoded.payload()).isInstanceOf(RunCompletedPayload.class);

        var decodedPayload = (RunCompletedPayload) decoded.payload();
        assertThat(decodedPayload.status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void shouldEncodeAndDecodeWithParentRunId() throws Exception {
        var payload = new RunStartedPayload("sub task", "/tmp", "gpt-4",
            "ASK", "none", "SubAgent");
        var envelope = codec.encode(payload, "run-sub", "run-parent", null, 1, NOW);

        String json = codec.serialize(envelope);
        var decoded = codec.deserialize(json);

        assertThat(decoded.runId()).isEqualTo("run-sub");
        assertThat(decoded.parentRunId()).isEqualTo("run-parent");
    }

    @Test
    void shouldEncodeAndDecodeWithTurnNumber() throws Exception {
        var payload = new ToolInvokedPayload("call-1", "read", "read file.txt",
            false, true, ToolRiskLevel.LOW, false, false);
        var envelope = codec.encode(payload, RUN_ID, null, 3, 5, NOW);

        String json = codec.serialize(envelope);
        var decoded = codec.deserialize(json);

        assertThat(decoded.turnNumber()).isEqualTo(3);
        assertThat(decoded.sequence()).isEqualTo(5);

        var decodedPayload = (ToolInvokedPayload) decoded.payload();
        assertThat(decodedPayload.toolName()).isEqualTo("read");
        assertThat(decodedPayload.riskLevel()).isEqualTo(ToolRiskLevel.LOW);
    }

    @Test
    void shouldEncodeAllEventTypes() {
        var testCases = List.of(
            new RunStartedPayload("task", "/tmp", "m", "ASK", "none", "ReAct"),
            new RunCompletedPayload(RunStatus.COMPLETED, null, null),
            new TurnStartedPayload(),
            new TurnCompletedPayload(1, true, false, null, null),
            new ContextPreparedPayload(5, 1000, 128000, false, "OK",
                Map.of("system", 500), false, 0, false),
            new ProviderCallStartedPayload("pc-1", "phase2", true),
            new ProviderCallCompletedPayload("pc-1", "phase2", true, 500, 100, false,
                1200, 0, false, null, null),
            new ToolInvokedPayload("tc-1", "read", "arg", false, true,
                ToolRiskLevel.LOW, false, false),
            new ToolCompletedPayload("tc-1", "read", true, 50, 1024,
                false, false, null, null, null),
            new ApprovalDecidedPayload("tc-1", "write", ToolRiskLevel.HIGH,
                "APPROVE", "USER", "ok"),
            new CompactTriggeredPayload(),
            new CompactCompletedPayload(20, 10, 5000, 2500, "WARN", "OK",
                Map.of(), Map.of(), 2, List.of("evict_old"), 300, false, null)
        );

        for (var payload : testCases) {
            var envelope = codec.encode(payload, RUN_ID, null, null, 1, NOW);
            assertThat(envelope.eventType()).isNotNull();
            assertThat(envelope.payload()).isSameAs(payload);
        }
    }

    @Test
    void shouldDeserializeUnknownEventTypeAsUnknownPayload() throws Exception {
        String json = """
            {
              "schemaVersion": 1,
              "eventId": "evt-001",
              "eventType": "future_event_type",
              "sequence": 1,
              "occurredAt": "2026-07-11T10:00:00Z",
              "recordedAt": "2026-07-11T10:00:01Z",
              "runId": "run-001",
              "parentRunId": null,
              "turnNumber": null,
              "payload": {"some": "data"}
            }""";

        var decoded = codec.deserialize(json);

        assertThat(decoded.eventType()).isEqualTo("future_event_type");
        assertThat(decoded.payload()).isInstanceOf(UnknownEventPayload.class);

        var unknown = (UnknownEventPayload) decoded.payload();
        assertThat(unknown.originalEventType()).isEqualTo("future_event_type");
        assertThat(unknown.raw()).isNotNull();
    }

    @Test
    void shouldDeserializeInvalidPayloadAsUnknownPayload() throws Exception {
        // valid event type but bad payload structure
        String json = """
            {
              "schemaVersion": 1,
              "eventId": "evt-001",
              "eventType": "run_started",
              "sequence": 1,
              "occurredAt": "2026-07-11T10:00:00Z",
              "recordedAt": "2026-07-11T10:00:01Z",
              "runId": "run-001",
              "parentRunId": null,
              "turnNumber": null,
              "payload": "not_an_object"
            }""";

        var decoded = codec.deserialize(json);
        // Should not throw despite payload not being an object
        assertThat(decoded.payload()).isNotNull();
    }

    @Test
    void shouldRejectNullPayloadType() {
        assertThrows(IllegalArgumentException.class, () ->
            codec.encode(null, RUN_ID, null, null, 1, NOW));
    }

    @Test
    void shouldGenerateUniqueEventIds() {
        var payload = new TurnStartedPayload();
        var env1 = codec.encode(payload, RUN_ID, null, null, 1, NOW);
        var env2 = codec.encode(payload, RUN_ID, null, null, 2, NOW);

        assertThat(env1.eventId()).isNotEqualTo(env2.eventId());
    }

    @Test
    void shouldRecordTimestamps() {
        var before = Instant.now();
        var payload = new TurnStartedPayload();
        var envelope = codec.encode(payload, RUN_ID, null, null, 1, NOW);

        // occurredAt is the passed-in time, recordedAt is generated
        assertThat(envelope.occurredAt()).isEqualTo(NOW);
        assertThat(envelope.recordedAt()).isNotNull();
    }

    @Test
    void shouldSerializeLineAppendsNewline() throws Exception {
        var payload = new RunStartedPayload("task", "/tmp", "m", "ASK", "none", "ReAct");
        var envelope = codec.encode(payload, RUN_ID, null, null, 1, NOW);

        String line = codec.serializeLine(envelope);
        assertThat(line).endsWith("\n");
        // Verify it's still valid JSON per line
        assertDoesNotThrow(() -> codec.deserialize(line.trim()));
    }

    @Test
    void shouldLookupEventTypeByClass() {
        assertThat(RunEventCodec.eventTypeFor(RunStartedPayload.class))
            .isEqualTo(RunEventType.RUN_STARTED);
        assertThat(RunEventCodec.eventTypeFor(RunCompletedPayload.class))
            .isEqualTo(RunEventType.RUN_COMPLETED);
        assertThat(RunEventCodec.eventTypeFor(ToolInvokedPayload.class))
            .isEqualTo(RunEventType.TOOL_INVOKED);
        assertThat(RunEventCodec.eventTypeFor(ApprovalDecidedPayload.class))
            .isEqualTo(RunEventType.APPROVAL_DECIDED);
    }

    @Test
    void shouldLookupPayloadClassByType() {
        assertThat(RunEventCodec.payloadClassFor(RunEventType.RUN_STARTED))
            .isEqualTo(RunStartedPayload.class);
        assertThat(RunEventCodec.payloadClassFor(RunEventType.COMPACT_COMPLETED))
            .isEqualTo(CompactCompletedPayload.class);
        assertThat(RunEventCodec.payloadClassFor("nonexistent")).isNull();
    }

    // ── P0-1: ToolRetryScheduledPayload codec ─────────────────────────

    @Test
    void shouldEncodeAndDecodeToolRetryScheduledRoundTrip() throws Exception {
        var payload = new ToolRetryScheduledPayload(
            "call-1", "grep", 2, 3, 150, "LOCAL_ERROR_NO_EFFECT", "TRANSIENT_READ_FAILURE");
        var envelope = codec.encode(payload, RUN_ID, null, 3, 5, NOW);

        String json = codec.serialize(envelope);
        var decoded = codec.deserialize(json);

        assertThat(decoded.eventType()).isEqualTo(RunEventType.TOOL_RETRY_SCHEDULED);
        assertThat(decoded.payload()).isInstanceOf(ToolRetryScheduledPayload.class);

        var p = (ToolRetryScheduledPayload) decoded.payload();
        assertThat(p.toolCallId()).isEqualTo("call-1");
        assertThat(p.toolName()).isEqualTo("grep");
        assertThat(p.attemptNumber()).isEqualTo(2);
        assertThat(p.maxAttempts()).isEqualTo(3);
        assertThat(p.delayMs()).isEqualTo(150);
        assertThat(p.failureClass()).isEqualTo("LOCAL_ERROR_NO_EFFECT");
        assertThat(p.retryReason()).isEqualTo("TRANSIENT_READ_FAILURE");
    }

    @Test
    void toolRetryScheduledRegisteredInTypeMap() {
        assertThat(RunEventCodec.eventTypeFor(ToolRetryScheduledPayload.class))
            .isEqualTo(RunEventType.TOOL_RETRY_SCHEDULED);
        assertThat(RunEventCodec.payloadClassFor(RunEventType.TOOL_RETRY_SCHEDULED))
            .isEqualTo(ToolRetryScheduledPayload.class);
    }

    // ── P0-2: 旧 JSON event 反序列化兼容 ─────────────────────────────

    @Test
    void shouldDeserializeOldToolCompletedWithDefaults() throws Exception {
        // 旧 JSON：没有 attemptCount、failureClassName 等新字段
        String oldJson = """
            {
              "schemaVersion": 1,
              "eventId": "evt-old",
              "eventType": "tool_completed",
              "sequence": 1,
              "occurredAt": "2026-07-11T10:00:00Z",
              "recordedAt": "2026-07-11T10:00:01Z",
              "runId": "run-001",
              "parentRunId": null,
              "turnNumber": 1,
              "payload": {
                "toolCallId": "call-1",
                "toolName": "read",
                "success": true,
                "durationMs": 50,
                "outputBytes": 1024,
                "truncated": false,
                "timedOut": false,
                "exitCode": null,
                "errorCode": null,
                "errorMessage": null
              }
            }""";

        var decoded = codec.deserialize(oldJson);
        assertThat(decoded.payload()).isInstanceOf(ToolCompletedPayload.class);

        var p = (ToolCompletedPayload) decoded.payload();
        assertThat(p.toolCallId()).isEqualTo("call-1");
        assertThat(p.attemptCount()).isEqualTo(0);  // Jackson default for absent int
        assertThat(p.failureClassName()).isNull();
        assertThat(p.inputComplete()).isFalse();     // Jackson default for absent boolean
    }

    @Test
    void shouldDeserializeOldCompactCompletedWithDefaults() throws Exception {
        String oldJson = """
            {
              "schemaVersion": 1,
              "eventId": "evt-old",
              "eventType": "compact_completed",
              "sequence": 1,
              "occurredAt": "2026-07-11T10:00:00Z",
              "recordedAt": "2026-07-11T10:00:01Z",
              "runId": "run-001",
              "parentRunId": null,
              "turnNumber": null,
              "payload": {
                "beforeMessages": 20,
                "afterMessages": 10,
                "beforeTokens": 5000,
                "afterTokens": 2500,
                "beforeStatus": "WARN",
                "afterStatus": "OK",
                "sectionsBefore": {},
                "sectionsAfter": {},
                "evictedGroups": 2,
                "appliedRules": ["evict_old"],
                "durationMs": 300,
                "failed": false,
                "errorCode": null
              }
            }""";

        var decoded = codec.deserialize(oldJson);
        assertThat(decoded.payload()).isInstanceOf(CompactCompletedPayload.class);

        var p = (CompactCompletedPayload) decoded.payload();
        assertThat(p.beforeMessages()).isEqualTo(20);
        assertThat(p.profile()).isNull();  // P1-A6 字段缺失 → Jackson default null
    }
}
