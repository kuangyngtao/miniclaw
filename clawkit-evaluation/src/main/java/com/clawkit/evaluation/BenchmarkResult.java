package com.clawkit.evaluation;

import com.clawkit.evaluation.scorer.Score;
import com.clawkit.evaluation.scorer.ScoreStatus;
import com.clawkit.observability.RunMetrics;
import com.clawkit.observability.RunSummary;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 单个 case 的执行结果，包含原始输出、O1 指标和 scorer 结果。
 */
public record BenchmarkResult(
    String caseId,
    String description,
    String category,
    boolean passed,
    FailureCategory failureCategory,
    String output,
    RunMetrics metrics,
    List<Score> scores,
    Path artifactDir,
    List<String> runIds,
    long durationMs,
    Throwable infrastructureError
) {
    public enum FailureCategory {
        NONE,
        TASK_FAILED,
        INFRASTRUCTURE_ERROR,
        REGRESSION
    }

    public RunSummary summary() {
        return metrics != null ? metrics.summary() : null;
    }

    public int turns() {
        var s = summary();
        return s != null ? s.turns() : 0;
    }

    public int toolCalls() {
        var s = summary();
        return s != null ? s.toolCalls() : 0;
    }

    public int toolFailures() {
        var s = summary();
        return s != null ? s.toolFailures() : 0;
    }

    public long durationMs() {
        return durationMs;
    }

    public int providerCalls() {
        return metrics != null ? metrics.provider().calls() : 0;
    }

    public int providerRetries() {
        return metrics != null ? metrics.provider().retries() : 0;
    }

    public int compactCount() {
        var s = summary();
        return s != null ? s.compactCount() : 0;
    }

    public long inputTokens() {
        return metrics != null ? metrics.provider().inputTokens() : 0;
    }

    public long outputTokens() {
        return metrics != null ? metrics.provider().outputTokens() : 0;
    }

    public Optional<Score> findScore(String scorerName) {
        return scores.stream()
            .filter(s -> s.name().equals(scorerName))
            .findFirst();
    }

    public boolean hasFailures() {
        return scores.stream().anyMatch(s -> s.status() == ScoreStatus.FAIL);
    }
}
