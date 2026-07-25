package com.clawkit.evaluation.scorer;

import com.clawkit.evaluation.BenchmarkResult;
import com.clawkit.evaluation.BenchmarkSpec;
import com.clawkit.evaluation.MetricBudget;
import com.clawkit.observability.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** P0-6：ContentInvariantScorer 基础测试 */
class ContentInvariantScorerTest {

    @TempDir
    Path tempDir;

    private static BenchmarkSpec specWithTags(Set<String> tags) {
        return new BenchmarkSpec("test", "test", tags, "",
            com.clawkit.engine.PermissionMode.AUTO,
            com.clawkit.engine.ThinkingMode.OFF,
            com.clawkit.engine.ExecutionMode.REACT,
            com.clawkit.evaluation.Fixture.empty(),
            List.of(), List.of(), MetricBudget.standard(),
            java.time.Duration.ofSeconds(30));
    }

    private static BenchmarkResult resultWithRuns(List<String> runIds) {
        return new BenchmarkResult("test", "test", "test", true,
            BenchmarkResult.FailureCategory.NONE, "", null, List.of(),
            null, runIds, 0, null);
    }

    private static BenchmarkResult emptyResult() {
        return resultWithRuns(List.of());
    }

    private void writeRun(String runId, RunEventPayload... payloads) {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        try (var recorder = new FileRunRecorder(tempDir)) {
            recorder.record(new RunStartedPayload(
                    "test", "/workspace", "model", "AUTO", "OFF", "REACT"),
                runId, null, null, now);
            int turn = 1;
            for (RunEventPayload payload : payloads) {
                recorder.record(payload, runId, null, turn++, now.plusSeconds(turn));
            }
            recorder.record(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
                runId, null, null, now.plusSeconds(100));
        }
    }

    private static ToolCompletedPayload toolResult(String toolCallId, int attempts) {
        return new ToolCompletedPayload(
            toolCallId, "read", true, 10, 2,
            false, false, null, null, null,
            attempts, null, "RETRY_ALLOWED", "SUCCESS",
            2, 2, 2, 1, 1,
            null, "LEGACY_V0", true);
    }

    @Test
    void passesWhenNoInvariants() {
        var spec = specWithTags(Set.of());
        var scorer = new ContentInvariantScorer();
        var score = scorer.score(spec, emptyResult(), tempDir);
        assertEquals(ScoreStatus.PASS, score.status());
    }

    @Test
    void maxAttemptsFailsWhenThereIsNoEventEvidence() {
        var spec = specWithTags(Set.of("invariant:MaxAttempts:3"));
        var scorer = new ContentInvariantScorer();
        var score = scorer.score(spec, emptyResult(), tempDir);
        assertEquals(ScoreStatus.FAIL, score.status());
    }

    @Test
    void noProviderCallAfterCompactFailurePassesWhenNoCompact() {
        var spec = specWithTags(Set.of("invariant:NoProviderCallAfterCompactFailure"));
        var scorer = new ContentInvariantScorer();
        var score = scorer.score(spec, emptyResult(), tempDir);
        assertEquals(ScoreStatus.PASS, score.status());
    }

    @Test
    void noDuplicateToolResultRejectsDuplicateFinalEvents() {
        writeRun("run-duplicate", toolResult("call-1", 1), toolResult("call-1", 1));
        var spec = specWithTags(Set.of("invariant:NoDuplicateToolResult"));

        var score = new ContentInvariantScorer().score(
            spec, resultWithRuns(List.of("run-duplicate")), tempDir);

        assertEquals(ScoreStatus.FAIL, score.status());
    }

    @Test
    void noDuplicateToolResultAcceptsDistinctFinalEvents() {
        writeRun("run-distinct", toolResult("call-1", 1), toolResult("call-2", 2));
        var spec = specWithTags(Set.of(
            "invariant:NoDuplicateToolResult",
            "invariant:MaxAttempts:3"));

        var score = new ContentInvariantScorer().score(
            spec, resultWithRuns(List.of("run-distinct")), tempDir);

        assertEquals(ScoreStatus.PASS, score.status());
    }

    @Test
    void unknownInvariantFailsEvenWhenEventsExist() {
        writeRun("run-unknown", toolResult("call-1", 1));
        var spec = specWithTags(Set.of("invariant:TypoThatMustNotPass"));

        var score = new ContentInvariantScorer().score(
            spec, resultWithRuns(List.of("run-unknown")), tempDir);

        assertEquals(ScoreStatus.FAIL, score.status());
    }

    @Test
    void providerCallAfterCompactFailureFails() {
        var failedCompact = new CompactCompletedPayload(
            10, 10, 1000, 1000, "HARD_LIMIT", "HARD_LIMIT",
            Map.of(), Map.of(), 0, List.of(), 5, true, "COMPACT_FAILED");
        writeRun("run-compact-failed", failedCompact,
            new ProviderCallStartedPayload("provider-2", "main", false));
        var spec = specWithTags(Set.of("invariant:NoProviderCallAfterCompactFailure"));

        var score = new ContentInvariantScorer().score(
            spec, resultWithRuns(List.of("run-compact-failed")), tempDir);

        assertEquals(ScoreStatus.FAIL, score.status());
    }
}
