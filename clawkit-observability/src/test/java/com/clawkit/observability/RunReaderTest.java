package com.clawkit.observability;

import com.clawkit.observability.model.RunMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunReaderTest {

    @TempDir
    Path tempDir;

    private RunReader reader;
    private Path runsDir;
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() throws Exception {
        reader = new RunReader(tempDir);
        runsDir = tempDir.resolve("runs");
        Files.createDirectories(runsDir);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private void writeEvents(String runId, List<String> jsonLines) throws Exception {
        Path runDir = runsDir.resolve(runId);
        Files.createDirectories(runDir);
        Files.write(runDir.resolve("events.jsonl"), jsonLines);
    }

    private void writeSummary(String runId, RunSummary summary) throws Exception {
        Path runDir = runsDir.resolve(runId);
        Files.createDirectories(runDir);
        MAPPER.writeValue(runDir.resolve("summary.json").toFile(), summary);
    }

    private RunEventEnvelope makeEnvelope(String eventType, int seq, String runId,
                                           Integer turnNumber, RunEventPayload payload) {
        return new RunEventEnvelope(1, "evt-" + seq, eventType, seq,
            Instant.parse("2026-07-11T10:00:0" + seq + "Z"),
            Instant.parse("2026-07-11T10:00:0" + seq + "Z"),
            runId, null, turnNumber, payload);
    }

    // ── Tests ───────────────────────────────────────────────────────

    @Test
    void shouldListRunsBySummaryStartTime() throws Exception {
        // Create summaries with different start times
        var summary1 = new RunSummary(1, "run-001", null, "task1", RunStatus.COMPLETED, true,
            Instant.parse("2026-07-11T10:00:00Z"), Instant.parse("2026-07-11T10:00:05Z"),
            5000, 3, 2, 0, 0, 2000, 500, 200, false, 4, 0, 2, 1, 1,
            1, 1, 0, 0, 3000, 128000, 0, 0,
            "ASK", "none", "ReAct", "/tmp", "gpt-4", null, null, List.of());
        var summary2 = new RunSummary(1, "run-002", null, "task2", RunStatus.COMPLETED, true,
            Instant.parse("2026-07-11T11:00:00Z"), Instant.parse("2026-07-11T11:00:03Z"),
            3000, 2, 1, 0, 0, 1000, 300, 100, false, 2, 0, 1, 0, 1,
            0, 0, 0, 0, 2000, 128000, 0, 0,
            "AUTO", "none", "ReAct", "/tmp", "gpt-4", null, null, List.of());

        writeSummary("run-001", summary1);
        writeSummary("run-002", summary2);

        var runs = reader.listRuns(10);
        assertThat(runs).hasSize(2);
        // run-002 is later, should come first
        assertThat(runs.get(0).runId()).isEqualTo("run-002");
        assertThat(runs.get(1).runId()).isEqualTo("run-001");
    }

    @Test
    void shouldReadEventsInSequenceOrder() throws Exception {
        var payload1 = new RunStartedPayload("task", "/tmp", "gpt-4", "ASK", "none", "ReAct");
        var payload2 = new TurnStartedPayload();
        var payload3 = new RunCompletedPayload(RunStatus.COMPLETED, null, null);

        var env1 = makeEnvelope(RunEventType.RUN_STARTED, 1, "run-001", null, payload1);
        var env2 = makeEnvelope(RunEventType.TURN_STARTED, 2, "run-001", 1, payload2);
        var env3 = makeEnvelope(RunEventType.RUN_COMPLETED, 3, "run-001", null, payload3);

        var codec = new RunEventCodec(MAPPER);
        writeEvents("run-001", List.of(
            codec.serialize(env1),
            codec.serialize(env2),
            codec.serialize(env3)));

        var result = reader.readEvents("run-001");
        assertThat(result.warnings()).isEmpty();
        assertThat(result.value()).hasSize(3);
        assertThat(result.value().get(0).sequence()).isEqualTo(1);
        assertThat(result.value().get(1).sequence()).isEqualTo(2);
        assertThat(result.value().get(2).sequence()).isEqualTo(3);
    }

    @Test
    void shouldSkipInvalidJsonLineWithWarning() throws Exception {
        var codec = new RunEventCodec(MAPPER);
        var payload = new RunStartedPayload("task", "/tmp", "gpt-4", "ASK", "none", "ReAct");
        var env = makeEnvelope(RunEventType.RUN_STARTED, 1, "run-001", null, payload);

        writeEvents("run-001", List.of(
            codec.serialize(env),
            "this is not valid json at all {{{",
            codec.serialize(env)  // duplicate — still valid JSON
        ));

        var result = reader.readEvents("run-001");
        assertThat(result.warnings()).isNotEmpty();
        assertThat(result.warnings().stream().anyMatch(
            w -> ReadWarning.INVALID_JSON.equals(w.code()))).isTrue();
        // Should have read the valid lines
        assertThat(result.value()).hasSize(2);
    }

    @Test
    void shouldPreserveUnknownEventWithWarning() throws Exception {
        // Write an unknown event type by creating a valid RunEventEnvelope
        // with UnknownEventPayload — simulates reading future-version events
        var env = new RunEventEnvelope(1, "evt-x", "future_event_v2", 1,
            Instant.parse("2026-07-11T10:00:01Z"),
            Instant.parse("2026-07-11T10:00:01Z"),
            "run-001", null, null,
            new UnknownEventPayload("future_event_v2", null));
        var codec = new RunEventCodec(MAPPER);
        writeEvents("run-001", List.of(codec.serialize(env)));

        var result = reader.readEvents("run-001");
        assertThat(result.warnings().stream().anyMatch(
            w -> ReadWarning.UNKNOWN_EVENT_TYPE.equals(w.code()))).isTrue();
        assertThat(result.value()).hasSize(1);
        assertThat(result.value().getFirst().payload()).isInstanceOf(UnknownEventPayload.class);
    }

    @Test
    void shouldRejectPathTraversalRunId() {
        assertThatThrownBy(() -> reader.readEvents("../escape"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.readSummary("a\\b"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReadLegacySummary() throws Exception {
        // Write an old-format summary.json (model/RunMetrics)
        var oldMetrics = new RunMetrics(
            "run-legacy", "legacy task",
            Instant.parse("2026-07-11T09:00:00Z"),
            Instant.parse("2026-07-11T09:00:05Z"),
            5000, RunStatus.COMPLETED, "COMPLETED",
            null, null, 3, 5, 1, 0,
            "ASK", "none", "ReAct", "/old", "gpt-3");

        Path runDir = runsDir.resolve("run-legacy");
        Files.createDirectories(runDir);
        MAPPER.writeValue(runDir.resolve("summary.json").toFile(), oldMetrics);

        var summary = reader.readSummary("run-legacy");
        assertThat(summary).isPresent();
        assertThat(summary.get().runId()).isEqualTo("run-legacy");
        assertThat(summary.get().taskSummary()).isEqualTo("legacy task");
        assertThat(summary.get().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary.get().turns()).isEqualTo(3);
        assertThat(summary.get().toolCalls()).isEqualTo(5);
    }

    @Test
    void shouldReadMetricsFromEvents() throws Exception {
        var codec = new RunEventCodec(MAPPER);
        var env1 = makeEnvelope(RunEventType.RUN_STARTED, 1, "run-001", null,
            new RunStartedPayload("task", "/tmp", "gpt-4", "ASK", "none", "ReAct"));
        var env2 = makeEnvelope(RunEventType.PROVIDER_CALL_COMPLETED, 2, "run-001", 1,
            new ProviderCallCompletedPayload("pc-1", "phase2", true, 500, 100, false, 1200, 0, false, null, null));
        var env3 = makeEnvelope(RunEventType.TOOL_INVOKED, 3, "run-001", 1,
            new ToolInvokedPayload("tc-1", "read", "arg", false, true, com.clawkit.tools.ToolRiskLevel.LOW, false, false));
        var env4 = makeEnvelope(RunEventType.TOOL_COMPLETED, 4, "run-001", 1,
            new ToolCompletedPayload("tc-1", "read", true, 50, 1024, false, false, null, null, null));
        var env5 = makeEnvelope(RunEventType.RUN_COMPLETED, 5, "run-001", null,
            new RunCompletedPayload(RunStatus.COMPLETED, null, null));

        writeEvents("run-001", List.of(
            codec.serialize(env1), codec.serialize(env2),
            codec.serialize(env3), codec.serialize(env4), codec.serialize(env5)));

        var result = reader.readMetrics("run-001");
        assertThat(result.value()).isNotNull();
        assertThat(result.value().summary().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.value().provider().calls()).isEqualTo(1);
        assertThat(result.value().provider().inputTokens()).isEqualTo(500);
        assertThat(result.value().tools().calls()).isEqualTo(1);
        assertThat(result.value().tools().lowRisk()).isEqualTo(1);
    }

    @Test
    void shouldReturnEmptyForNonexistentRun() throws Exception {
        assertThat(reader.readSummary("no-such-run")).isEmpty();

        var events = reader.readEvents("no-such-run");
        assertThat(events.value()).isEmpty();
        assertThat(events.warnings()).isNotEmpty();
    }

    @Test
    void shouldHandleEmptyEventsFile() throws Exception {
        writeEvents("run-empty", List.of());
        // Also need a summary or the directory check fails
        writeSummary("run-empty", RunSummary.initial("run-empty", null));

        var result = reader.readEvents("run-empty");
        assertThat(result.value()).isEmpty();
    }
}
