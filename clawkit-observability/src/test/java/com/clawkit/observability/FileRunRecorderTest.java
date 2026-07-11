package com.clawkit.observability;

import com.clawkit.tools.ToolRiskLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileRunRecorderTest {

    @TempDir
    Path tempDir;

    private FileRunRecorder recorder;
    private RunReader reader;
    private static final Instant T0 = Instant.parse("2026-07-11T10:00:00Z");

    @BeforeEach
    void setUp() {
        recorder = new FileRunRecorder(tempDir);
        reader = new RunReader(tempDir);
    }

    @AfterEach
    void tearDown() {
        recorder.close();
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private void startRun(String runId) {
        recorder.record(new RunStartedPayload(
            ObservabilityRedactor.summarizeTask("Fix bug #42\nin login"),
            "/workspace", "gpt-4", "ASK", "none", "ReAct"),
            runId, null, null, T0);
    }

    private void completeRun(String runId, RunStatus status) {
        recorder.record(new RunCompletedPayload(status,
            status == RunStatus.COMPLETED ? null : "ERR",
            status == RunStatus.COMPLETED ? null : "Something went wrong"),
            runId, null, null, T0.plusSeconds(10));
    }

    private void record(RunEventPayload p, String runId, Integer turn, Instant t) {
        recorder.record(p, runId, null, turn, t);
    }

    // ── Tests ───────────────────────────────────────────────────────

    @Test
    void shouldCreateEventsAndSummaryForCompletedRun() throws Exception {
        startRun("run-001");
        record(new TurnStartedPayload(), "run-001", 1, T0.plusSeconds(1));
        record(new TurnCompletedPayload(2, true, false, null, null), "run-001", 1, T0.plusSeconds(1));
        record(new ProviderCallCompletedPayload(null, "phase2", true, 500, 100,
            false, 1200, 0, false, null, null), "run-001", 1, T0.plusSeconds(1));
        record(new ToolInvokedPayload("call-1", "read", "read file", false, true,
            ToolRiskLevel.LOW, false, false), "run-001", 1, T0.plusSeconds(1));
        record(new ToolCompletedPayload("call-1", "read", true, 50, 1024,
            false, false, null, null, null), "run-001", 1, T0.plusSeconds(1));
        completeRun("run-001", RunStatus.COMPLETED);

        Path eventsFile = tempDir.resolve("runs").resolve("run-001").resolve("events.jsonl");
        assertThat(Files.exists(eventsFile)).isTrue();

        Optional<RunSummary> summary = reader.readSummary("run-001");
        assertThat(summary).isPresent();
        assertThat(summary.get().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary.get().finalized()).isTrue();

        var eventsResult = reader.readEvents("run-001");
        assertThat(eventsResult.value()).hasSize(7);
    }

    @Test
    void shouldAssignMonotonicSequencePerRun() throws Exception {
        startRun("run-001");
        record(new TurnStartedPayload(), "run-001", 1, T0.plusSeconds(1));
        record(new TurnStartedPayload(), "run-001", 2, T0.plusSeconds(2));
        completeRun("run-001", RunStatus.COMPLETED);

        var events = reader.readEvents("run-001").value();
        var sequences = events.stream().map(RunEventEnvelope::sequence).toList();
        assertThat(sequences).isSorted();
        assertThat(sequences.getFirst()).isEqualTo(1);
    }

    @Test
    void shouldFinalizeFailedRun() throws Exception {
        startRun("run-001");
        completeRun("run-001", RunStatus.LLM_ERROR);

        var summary = reader.readSummary("run-001").get();
        assertThat(summary.status()).isEqualTo(RunStatus.LLM_ERROR);
        assertThat(summary.finalized()).isTrue();
    }

    @Test
    void shouldFinalizeInterruptedRun() throws Exception {
        startRun("run-001");
        completeRun("run-001", RunStatus.INTERRUPTED);

        var summary = reader.readSummary("run-001").get();
        assertThat(summary.status()).isEqualTo(RunStatus.INTERRUPTED);
    }

    @Test
    void shouldMarkActiveRunIncompleteOnClose() throws Exception {
        recorder.record(new RunStartedPayload("incomplete task", "/tmp", "m",
            "ASK", "none", "ReAct"), "run-incomplete", null, null, T0);
        recorder.close();

        var summary = reader.readSummary("run-incomplete");
        assertThat(summary).isPresent();
        assertThat(summary.get().finalized()).isFalse();
        assertThat(summary.get().warnings()).contains("RECORDER_CLOSED_WITHOUT_RUN_COMPLETED");
    }

    @Test
    void shouldRejectDuplicateRunStarted() throws Exception {
        startRun("run-001");
        recorder.record(new RunStartedPayload("other task", "/other", "gpt-4",
            "AUTO", "none", "ReAct"), "run-001", null, null, T0.plusSeconds(1));
        completeRun("run-001", RunStatus.COMPLETED);

        var summary = reader.readSummary("run-001").get();
        assertThat(summary.taskSummary()).isEqualTo("Fix bug #42 in login");
    }

    @Test
    void shouldIgnoreEventWithoutRunStarted() {
        recorder.record(new TurnStartedPayload(), "no-such-run", null, 1, T0);
        // Should not throw
    }

    @Test
    void shouldRejectLateEventAfterCompletion() throws Exception {
        startRun("run-001");
        completeRun("run-001", RunStatus.COMPLETED);

        recorder.record(new TurnStartedPayload(), "run-001", null, 99, T0.plusSeconds(999));

        var events = reader.readEvents("run-001").value();
        long turnStartedCount = events.stream()
            .filter(e -> RunEventType.TURN_STARTED.equals(e.eventType()))
            .count();
        assertThat(turnStartedCount).isEqualTo(0);
    }

    @Test
    void shouldKeepConcurrentRunsIsolated() throws Exception {
        int threadCount = 4;
        var latch = new CountDownLatch(1);
        var done = new CountDownLatch(threadCount);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                final String rid = "concurrent-" + i;
                final String taskLabel = "task " + i;
                executor.submit(() -> {
                    try {
                        latch.await();
                        recorder.record(new RunStartedPayload(taskLabel, "/tmp", "m",
                            "A", "n", "R"), rid, null, null, T0);
                        recorder.record(new TurnStartedPayload(), rid, null, 1, T0.plusSeconds(1));
                        recorder.record(new TurnCompletedPayload(1, false, false, null, null),
                            rid, null, 1, T0.plusSeconds(1));
                        recorder.record(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
                            rid, null, null, T0.plusSeconds(5));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            latch.countDown();
            done.await();
        }

        for (int i = 0; i < threadCount; i++) {
            String rid = "concurrent-" + i;
            var events = reader.readEvents(rid).value();
            assertThat(events).hasSize(4);
            assertThat(events).allMatch(e -> rid.equals(e.runId()));
            var seqs = events.stream().map(RunEventEnvelope::sequence).toList();
            assertThat(seqs).containsExactly(1L, 2L, 3L, 4L);
        }
    }

    @Test
    void shouldAtomicallyReplaceSummary() throws Exception {
        startRun("run-001");

        Path summaryFile = tempDir.resolve("runs").resolve("run-001").resolve("summary.json");
        assertThat(Files.exists(summaryFile)).isTrue();
        var initial = reader.readSummary("run-001").get();
        assertThat(initial.finalized()).isFalse();

        completeRun("run-001", RunStatus.COMPLETED);

        var final1 = reader.readSummary("run-001").get();
        assertThat(final1.finalized()).isTrue();
        assertThat(final1.status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void shouldRejectInvalidRunId() {
        assertThatThrownBy(() -> recorder.record(new RunStartedPayload("t", "/tmp", "m",
            "A", "n", "R"), "../escape", null, null, T0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectPathTraversalRunId() {
        assertThatThrownBy(() -> recorder.record(new RunStartedPayload("t", "/tmp", "m",
            "A", "n", "R"), "a/../b", null, null, T0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldWriteTaskSummaryWithNewlinesRemoved() throws Exception {
        recorder.record(new RunStartedPayload(
            ObservabilityRedactor.summarizeTask("Fix\nthe  bug\r\nnow"),
            "/tmp", "m", "A", "n", "R"), "run-001", null, null, T0);
        completeRun("run-001", RunStatus.COMPLETED);

        var summary = reader.readSummary("run-001").get();
        assertThat(summary.taskSummary()).isEqualTo("Fix the bug now");
    }

    @Test
    void shouldTruncateLongTaskSummary() throws Exception {
        String longTask = "X".repeat(300);
        recorder.record(new RunStartedPayload(
            ObservabilityRedactor.summarizeTask(longTask), "/tmp", "m", "A", "n", "R"),
            "run-001", null, null, T0);
        completeRun("run-001", RunStatus.COMPLETED);

        var summary = reader.readSummary("run-001").get();
        assertThat(summary.taskSummary()).hasSize(160);
        assertThat(summary.taskSummary()).endsWith("...");
    }
}
