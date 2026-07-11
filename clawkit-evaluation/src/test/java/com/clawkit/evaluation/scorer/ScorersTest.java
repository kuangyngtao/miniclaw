package com.clawkit.evaluation.scorer;

import com.clawkit.evaluation.BenchmarkResult;
import com.clawkit.evaluation.BenchmarkSpec;
import com.clawkit.evaluation.MetricBudget;
import com.clawkit.engine.PermissionMode;
import com.clawkit.observability.*;
import com.clawkit.observability.RunMetrics.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScorersTest {

    @TempDir
    Path tempDir;

    private BenchmarkResult resultWithMetrics(RunMetrics metrics) {
        return new BenchmarkResult("test", "desc", "cat", true,
            BenchmarkResult.FailureCategory.NONE, "output", metrics,
            List.of(), tempDir, List.of("run-1"), 100, null);
    }

    private RunMetrics fakeMetrics(RunStatus status, int turns, int tools,
                                    int toolFails, int providerCalls, int compactions) {
        var summary = new RunSummary(1, "run-1", null, "test task",
            status, status != RunStatus.RUNNING,
            Instant.now(), Instant.now().plusSeconds(5), 5000,
            turns,
            providerCalls, 0, 0, 1000, 500, 200, false,
            tools, toolFails, tools - toolFails, 0, 0,
            0, 0, 0, 0,
            3000, 128000, compactions, 0,
            "AUTO", "OFF", "ReAct", "/tmp", "test-model",
            null, null, List.of());
        var providerMetrics = new ProviderMetrics(providerCalls, 0, 0, 1000, 500, 200, false);
        var toolMetrics = new ToolMetrics(tools, toolFails, tools - toolFails, 0, 0);
        var contextMetrics = new ContextMetrics(3000, 128000, compactions, 0);
        var compactMetrics = new CompactMetrics(compactions, 0);
        var approvalMetrics = new ApprovalMetrics(0, 0, 0, 0);
        return new RunMetrics("run-1", summary, providerMetrics, toolMetrics,
            contextMetrics, compactMetrics, approvalMetrics, List.of());
    }

    @Test
    void runStatusScorerShouldPassWhenCompleted() {
        var scorer = new RunStatusScorer();
        var metrics = fakeMetrics(RunStatus.COMPLETED, 2, 2, 0, 2, 0);
        var result = resultWithMetrics(metrics);
        var spec = BenchmarkSpec.builder("test").build();

        var score = scorer.score(spec, result, tempDir);
        assertThat(score.status()).isEqualTo(ScoreStatus.PASS);
    }

    @Test
    void runStatusScorerShouldFailWhenNotCompleted() {
        var scorer = new RunStatusScorer(RunStatus.COMPLETED);
        var metrics = fakeMetrics(RunStatus.LLM_ERROR, 1, 0, 0, 1, 0);
        var result = resultWithMetrics(metrics);
        var spec = BenchmarkSpec.builder("test").build();

        var score = scorer.score(spec, result, tempDir);
        assertThat(score.status()).isEqualTo(ScoreStatus.FAIL);
    }

    @Test
    void metricBudgetScorerShouldPassWithinBudget() {
        var scorer = new MetricBudgetScorer();
        var metrics = fakeMetrics(RunStatus.COMPLETED, 3, 5, 1, 4, 1);
        var result = resultWithMetrics(metrics);
        var spec = BenchmarkSpec.builder("test")
            .budget(MetricBudget.standard())
            .build();

        var score = scorer.score(spec, result, tempDir);
        assertThat(score.status()).isEqualTo(ScoreStatus.PASS);
    }

    @Test
    void metricBudgetScorerShouldFailWhenExceeded() {
        var scorer = new MetricBudgetScorer();
        var metrics = fakeMetrics(RunStatus.COMPLETED, 15, 10, 5, 12, 3);
        var result = resultWithMetrics(metrics);
        var spec = BenchmarkSpec.builder("test")
            .budget(MetricBudget.tight())
            .build();

        var score = scorer.score(spec, result, tempDir);
        assertThat(score.status()).isEqualTo(ScoreStatus.FAIL);
    }

    @Test
    void safetyScorerShouldPassInPlanModeWithNoHighRiskTools() {
        var scorer = new SafetyScorer();
        var summary = new RunSummary(1, "run-1", null, "task",
            RunStatus.COMPLETED, true, Instant.now(), Instant.now(), 5000,
            2, 2, 0, 0, 1000, 500, 200, false,
            2, 0, 2, 0, 0, 0, 0, 0, 0, 3000, 128000, 0, 0,
            "PLAN", "OFF", "ReAct", "/tmp", "m", null, null, List.of());
        var toolMetrics = new ToolMetrics(2, 0, 2, 0, 0);
        var providerMetrics = new ProviderMetrics(2, 0, 0, 1000, 500, 200, false);
        var metrics = new RunMetrics("run-1", summary, providerMetrics, toolMetrics,
            new ContextMetrics(3000, 128000, 0, 0),
            new CompactMetrics(0, 0),
            new ApprovalMetrics(0, 0, 0, 0), List.of());
        var result = resultWithMetrics(metrics);
        var spec = BenchmarkSpec.builder("test")
            .permissionMode(PermissionMode.PLAN)
            .build();

        var score = scorer.score(spec, result, tempDir);
        assertThat(score.status()).isEqualTo(ScoreStatus.PASS);
    }

    @Test
    void safetyScorerShouldFailWhenHighRiskToolsInPlan() {
        var scorer = new SafetyScorer();
        var summary = new RunSummary(1, "run-1", null, "task",
            RunStatus.COMPLETED, true, Instant.now(), Instant.now(), 5000,
            2, 3, 0, 0, 1000, 500, 200, false,
            3, 0, 2, 0, 1, 0, 0, 0, 0, 3000, 128000, 0, 0,
            "PLAN", "OFF", "ReAct", "/tmp", "m", null, null, List.of());
        var toolMetrics = new ToolMetrics(3, 0, 2, 0, 1);
        var providerMetrics = new ProviderMetrics(3, 0, 0, 1000, 500, 200, false);
        var metrics = new RunMetrics("run-1", summary, providerMetrics, toolMetrics,
            new ContextMetrics(3000, 128000, 0, 0),
            new CompactMetrics(0, 0),
            new ApprovalMetrics(0, 0, 0, 0), List.of());
        var result = resultWithMetrics(metrics);
        var spec = BenchmarkSpec.builder("test")
            .permissionMode(PermissionMode.PLAN)
            .build();

        var score = scorer.score(spec, result, tempDir);
        assertThat(score.status()).isEqualTo(ScoreStatus.FAIL);
    }

    @Test
    void fileStateScorerShouldVerifyFilesExist() throws Exception {
        Path file = tempDir.resolve("output.txt");
        Files.writeString(file, "hello world");

        var scorer = FileStateScorer.exact(Map.of("output.txt", "hello world"));
        var metrics = fakeMetrics(RunStatus.COMPLETED, 2, 2, 0, 2, 0);
        var result = resultWithMetrics(metrics);
        var spec = BenchmarkSpec.builder("test").build();

        var score = scorer.score(spec, result, tempDir);
        assertThat(score.status()).isEqualTo(ScoreStatus.PASS);
    }

    @Test
    void fileStateScorerShouldFailWhenFileMissing() {
        var scorer = FileStateScorer.exact(Map.of("nonexistent.txt", "content"));
        var metrics = fakeMetrics(RunStatus.COMPLETED, 2, 2, 0, 2, 0);
        var result = resultWithMetrics(metrics);
        var spec = BenchmarkSpec.builder("test").build();

        var score = scorer.score(spec, result, tempDir);
        assertThat(score.status()).isEqualTo(ScoreStatus.FAIL);
    }

    @Test
    void eventInvariantScorerShouldPassWithEvents() {
        var scorer = new EventInvariantScorer();
        var metrics = fakeMetrics(RunStatus.COMPLETED, 2, 2, 0, 2, 0);
        var result = resultWithMetrics(metrics);
        var spec = BenchmarkSpec.builder("test").build();

        var score = scorer.score(spec, result, tempDir);
        assertThat(score.status()).isEqualTo(ScoreStatus.PASS);
    }

    @Test
    void eventInvariantScorerShouldFailWithoutMetrics() {
        var scorer = new EventInvariantScorer();
        var result = new BenchmarkResult("test", "desc", "cat", false,
            BenchmarkResult.FailureCategory.TASK_FAILED, "", null,
            List.of(), tempDir, List.of(), 100, null);
        var spec = BenchmarkSpec.builder("test").build();

        var score = scorer.score(spec, result, tempDir);
        assertThat(score.status()).isEqualTo(ScoreStatus.FAIL);
    }

    @Test
    void scriptConsumptionScorerShouldPassWhenRunCompleted() {
        var scorer = new ScriptConsumptionScorer();
        var metrics = fakeMetrics(RunStatus.COMPLETED, 2, 2, 0, 2, 0);
        var result = resultWithMetrics(metrics);
        var spec = BenchmarkSpec.builder("test").build();

        var score = scorer.score(spec, result, tempDir);
        assertThat(score.status()).isEqualTo(ScoreStatus.PASS);
    }
}
