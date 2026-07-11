package com.clawkit.evaluation;

import com.clawkit.engine.PermissionMode;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.evaluation.scorer.*;
import com.clawkit.provider.LLMException;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldRunReadSearchCaseToCompletion() {
        var spec = BenchmarkCatalog.readSearch();
        var runner = new BenchmarkRunner(tempDir.resolve("benchmark-runs"));
        var result = runner.runCase(spec);

        assertThat(result.caseId()).isEqualTo("read-search");
        assertThat(result.metrics()).isNotNull();
        assertThat(result.runIds()).isNotEmpty();
        assertThat(result.durationMs()).isPositive();
    }

    @Test
    void shouldRunProviderFailureCase() {
        var spec = BenchmarkCatalog.providerFailure();
        var runner = new BenchmarkRunner(tempDir.resolve("benchmark-runs"));
        var result = runner.runCase(spec);

        assertThat(result.caseId()).isEqualTo("provider-failure");
        // Provider failure should still produce metrics
        assertThat(result.metrics()).isNotNull();
    }

    @Test
    void shouldRunPlanWriteBlockCase() {
        var spec = BenchmarkCatalog.planWriteBlock();
        var runner = new BenchmarkRunner(tempDir.resolve("benchmark-runs"));
        var result = runner.runCase(spec);

        assertThat(result.caseId()).isEqualTo("plan-write-block");
        assertThat(result.metrics()).isNotNull();
        // Safety scorer should pass (no high-risk tools executed in PLAN)
        var safetyScore = result.findScore("SafetyScorer");
        assertThat(safetyScore).isPresent();
    }

    @Test
    void shouldRunAllPr1Cases() {
        var runner = new BenchmarkRunner(tempDir.resolve("benchmark-runs"));
        var report = runner.runAll(BenchmarkCatalog.pr1Cases());

        assertThat(report.totalCases()).isEqualTo(3);
        assertThat(report.results()).hasSize(3);
        for (var r : report.results()) {
            assertThat(r.runIds()).isNotEmpty();
            assertThat(r.metrics()).isNotNull();
        }
    }

    @Test
    void shouldRunAll15Cases() {
        var runner = new BenchmarkRunner(tempDir.resolve("benchmark-runs15"));
        var report = runner.runAll(BenchmarkCatalog.allCases());

        assertThat(report.totalCases()).isEqualTo(16);
        // Each case must produce metrics (O1 events working)
        for (var r : report.results()) {
            assertThat(r.metrics()).as("metrics for " + r.caseId()).isNotNull();
            assertThat(r.runIds()).as("runIds for " + r.caseId()).isNotEmpty();
        }
    }

    @Test
    void shouldRunNewCasesIndividually() {
        var runner = new BenchmarkRunner(tempDir.resolve("benchmark-runs-new"));
        var newCases = List.of(
            BenchmarkCatalog.globGrep(),
            BenchmarkCatalog.editFix(),
            BenchmarkCatalog.runVerification(),
            BenchmarkCatalog.toolFailureRecovery(),
            BenchmarkCatalog.longOutputTruncation(),
            BenchmarkCatalog.multiTurn(),
            BenchmarkCatalog.deadLoopStop(),
            BenchmarkCatalog.twoStage(),
            BenchmarkCatalog.askApproveReject());

        for (var spec : newCases) {
            var result = runner.runCase(spec);
            assertThat(result.metrics())
                .as("metrics for " + spec.id())
                .isNotNull();
        }
    }

    @Test
    void shouldHandleCaseWithNoTools() {
        var spec = BenchmarkSpec.builder("no-tools")
            .category("minimal")
            .prompt("Hello, world")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.OFF)
            .script(List.of(
                ScriptedStep.text("Hello! How can I help you?")
            ))
            .scorers(List.of(
                new RunStatusScorer(),
                new EventInvariantScorer(),
                new MetricBudgetScorer()))
            .budget(MetricBudget.tight())
            .timeout(Duration.ofSeconds(10))
            .build();

        var runner = new BenchmarkRunner(tempDir.resolve("benchmark-runs"));
        var result = runner.runCase(spec);

        assertThat(result.metrics()).isNotNull();
        assertThat(result.runIds()).isNotEmpty();
    }

    @Test
    void shouldHandleScriptExhaustedCorrectly() {
        // Script has 2 steps but engine will need 3 calls (run + after tool)
        // → script exhausts, engine should handle LLM error
        var spec = BenchmarkSpec.builder("short-script")
            .category("error")
            .prompt("Do a read and report")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.OFF)
            .script(List.of(
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_1", "read", readArgs("test.txt"))))),
                ScriptedStep.text("Done reading.")))
            .scorers(List.of(
                new MetricBudgetScorer()))
            .budget(MetricBudget.liberal())
            .timeout(Duration.ofSeconds(10))
            .build();

        var runner = new BenchmarkRunner(tempDir.resolve("benchmark-runs"));
        var result = runner.runCase(spec);

        // Should not crash — engine handles script exhaustion gracefully
        assertThat(result.metrics()).isNotNull();
    }

    @Test
    void shouldProduceValidBenchmarkReport() {
        var runner = new BenchmarkRunner(tempDir.resolve("benchmark-runs"));
        var report = runner.runAll(BenchmarkCatalog.pr1Cases());

        assertThat(report.evaluationId()).isNotBlank();
        assertThat(report.totalCases()).isEqualTo(3);
        assertThat(report.passRate()).isBetween(0.0, 1.0);
        assertThat(report.avgTurns()).isGreaterThanOrEqualTo(0);
        assertThat(report.avgDurationMs()).isGreaterThanOrEqualTo(0);
    }

    private static com.fasterxml.jackson.databind.JsonNode readArgs(String path) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("path", path);
        return args;
    }
}
