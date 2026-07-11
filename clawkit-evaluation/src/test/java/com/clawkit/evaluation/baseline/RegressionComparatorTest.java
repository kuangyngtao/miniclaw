package com.clawkit.evaluation.baseline;

import com.clawkit.evaluation.BenchmarkReport;
import com.clawkit.evaluation.BenchmarkResult;
import com.clawkit.observability.RunMetrics;
import com.clawkit.observability.RunSummary;
import com.clawkit.observability.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegressionComparatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT);

    @TempDir
    Path tempDir;

    private BenchmarkReport makeReport(String caseId, boolean passed, int turns,
                                        int toolCalls, int toolFailures, long durationMs,
                                        int providerCalls) {
        var summary = new RunSummary(1, "run-1", null, "task",
            passed ? RunStatus.COMPLETED : RunStatus.LLM_ERROR,
            true, Instant.now(), Instant.now().plusSeconds(5), durationMs,
            turns, providerCalls, 0, 0, 1000, 500, 200, false,
            toolCalls, toolFailures, 3, 0, 0,
            0, 0, 0, 0, 0, 128000, 0, 0,
            "AUTO", "OFF", "ReAct", "/tmp", "m", null, null, List.of());
        var pm = new RunMetrics.ProviderMetrics(providerCalls, 0, 0, 1000, 500, 200, false);
        var tm = new RunMetrics.ToolMetrics(toolCalls, toolFailures, 3, 0, 0);
        var cm = new RunMetrics.ContextMetrics(0, 128000, 0, 0);
        var comp = new RunMetrics.CompactMetrics(0, 0);
        var am = new RunMetrics.ApprovalMetrics(0, 0, 0, 0);
        var metrics = new RunMetrics("run-1", summary, pm, tm, cm, comp, am, List.of());
        var result = new BenchmarkResult(caseId, "", "cat", passed,
            passed ? BenchmarkResult.FailureCategory.NONE : BenchmarkResult.FailureCategory.TASK_FAILED,
            "", metrics, List.of(), null, List.of("run-1"), durationMs, null);
        return new BenchmarkReport("eval-1", "1.0", "abc", System.currentTimeMillis(),
            durationMs, List.of(result), passed ? "UNCHANGED" : "DEGRADED", null);
    }

    @Test
    void shouldSaveAndLoadBaseline() throws Exception {
        var report = makeReport("test-case", true, 3, 5, 1, 200, 3);
        var baseline = BaselineData.from(report, "abc123");
        Path path = tempDir.resolve("baseline.json");
        BaselineStore.save(path, baseline);

        var loaded = BaselineStore.load(path);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().suiteId()).isEqualTo("clawkit-runtime-regression");
        assertThat(loaded.get().summary().totalCases()).isEqualTo(1);
        assertThat(loaded.get().cases()).containsKey("test-case");
    }

    @Test
    void shouldDetectNoRegression() {
        var report = makeReport("test-case", true, 3, 5, 1, 200, 3);
        var baseline = BaselineData.from(report, "abc");

        var regression = RegressionComparator.compare(report, baseline);
        assertThat(regression.verdict()).isEqualTo(Verdict.UNCHANGED);
    }

    @Test
    void shouldDetectDegradedWhenPassToFail() {
        var baselineReport = makeReport("test-case", true, 3, 5, 1, 200, 3);
        var currentReport = makeReport("test-case", false, 1, 0, 0, 50, 1);
        var baseline = BaselineData.from(baselineReport, "abc");

        var regression = RegressionComparator.compare(currentReport, baseline);
        assertThat(regression.verdict()).isEqualTo(Verdict.DEGRADED);
    }

    @Test
    void shouldDetectDegradedWhenTurnsIncrease() {
        var baselineReport = makeReport("test-case", true, 3, 5, 1, 200, 3);
        var currentReport = makeReport("test-case", true, 8, 12, 3, 500, 8);
        var baseline = BaselineData.from(baselineReport, "abc");

        var regression = RegressionComparator.compare(currentReport, baseline);
        // Turns: baseline=3, current=8, limit=max(3+1, ceil(3*1.1))=max(4,4)=4. 8>4 → DEGRADED
        assertThat(regression.verdict()).isEqualTo(Verdict.DEGRADED);
    }

    @Test
    void shouldDetectIncompatibleBaseline() {
        var baselineReport = makeReport("case-a", true, 3, 5, 1, 200, 3);
        var currentReport = makeReport("case-b", true, 3, 5, 1, 200, 3);
        var baseline = BaselineData.from(baselineReport, "abc");

        var regression = RegressionComparator.compare(currentReport, baseline);
        assertThat(regression.verdict()).isEqualTo(Verdict.INCOMPATIBLE_BASELINE);
    }

    @Test
    void shouldReturnEmptyForMissingBaseline() {
        var loaded = BaselineStore.load(tempDir.resolve("nonexistent.json"));
        assertThat(loaded).isEmpty();
    }
}
