package com.clawkit.evaluation.scorer;

import com.clawkit.evaluation.BenchmarkResult;
import com.clawkit.evaluation.BenchmarkSpec;
import com.clawkit.evaluation.MetricBudget;
import com.clawkit.observability.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
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

    private static BenchmarkResult emptyResult() {
        return new BenchmarkResult("test", "test", "test", true,
            BenchmarkResult.FailureCategory.NONE, "", null, List.of(),
            null, List.of(), 0, null);
    }

    @Test
    void passesWhenNoInvariants() {
        var spec = specWithTags(Set.of());
        var scorer = new ContentInvariantScorer();
        var score = scorer.score(spec, emptyResult(), tempDir);
        assertEquals(ScoreStatus.PASS, score.status());
    }

    @Test
    void maxAttemptsCheckPassesWhenNoMetrics() {
        var spec = specWithTags(Set.of("invariant:MaxAttempts:3"));
        var scorer = new ContentInvariantScorer();
        var score = scorer.score(spec, emptyResult(), tempDir);
        assertEquals(ScoreStatus.PASS, score.status());
    }

    @Test
    void noProviderCallAfterCompactFailurePassesWhenNoCompact() {
        var spec = specWithTags(Set.of("invariant:NoProviderCallAfterCompactFailure"));
        var scorer = new ContentInvariantScorer();
        var score = scorer.score(spec, emptyResult(), tempDir);
        assertEquals(ScoreStatus.PASS, score.status());
    }
}
