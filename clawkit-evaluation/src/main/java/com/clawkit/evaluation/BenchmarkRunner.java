package com.clawkit.evaluation;

import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.observability.FileRunRecorder;
import com.clawkit.observability.RunMetricsProjector;
import com.clawkit.observability.RunReader;
import com.clawkit.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Benchmark 编排引擎。
 * 按 BenchmarkSpec 定义创建 engine、执行、收集 O1 指标、运行 scorer。
 */
public class BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

    private final Path outputBaseDir;
    private final String evaluationId;

    public BenchmarkRunner(Path outputBaseDir) {
        this.outputBaseDir = outputBaseDir;
        this.evaluationId = Instant.now().toString().replace(":", "-").substring(0, 19);
    }

    /** 运行单个 case */
    public BenchmarkResult runCase(BenchmarkSpec spec) {
        Path caseOutputDir = outputBaseDir.resolve(spec.id());
        Path runsDir = caseOutputDir.resolve("runs");
        Path workDirBase = caseOutputDir.resolve("workspace");

        FixtureManager fixtureMgr = new FixtureManager(workDirBase);
        List<String> runIds = new ArrayList<>();
        Throwable infraError = null;
        long startMs = System.currentTimeMillis();

        try {
            Files.createDirectories(runsDir);
            Files.createDirectories(workDirBase);

            // 1. Setup fixture
            Path workDir = fixtureMgr.setup(spec.fixture(), spec.id());

            // 2. Build ScriptedProvider
            var provider = new ScriptedProvider(spec.script());

            // 3. Build ToolRegistry（真实工具 + fake 工具）
            ToolRegistry registry = buildRegistry(spec, workDir);

            // 4. Setup O1 recorder pipeline
            var fileRecorder = new FileRunRecorder(caseOutputDir);
            var capturing = new CapturingRecorder(fileRecorder);

            // 5. Create engine
            AgentEngine engine = new AgentEngine(provider, registry,
                workDir.toString(), spec.thinkingMode());
            engine.addRecorder(capturing);
            engine.setPermissionMode(spec.permissionMode());

            // 6. Run (with timeout)
            String output;
            try {
                output = engine.run(spec.prompt());
            } catch (Exception e) {
                output = "[engine error: " + e.getMessage() + "]";
                infraError = e;
            } finally {
                fileRecorder.close();
            }

            long durationMs = System.currentTimeMillis() - startMs;
            runIds = capturing.orderedRunIds();

            // 7. Read O1 metrics
            com.clawkit.observability.RunMetrics metrics = null;
            String rootId = capturing.rootRunId();
            if (rootId != null) {
                try {
                    var reader = new RunReader(caseOutputDir);
                    var events = reader.readEvents(rootId);
                    if (events.value() != null && !events.value().isEmpty()) {
                        metrics = RunMetricsProjector.project(events.value());
                    }
                } catch (Exception e) {
                    log.warn("Failed to read metrics for {}: {}", spec.id(), e.getMessage());
                }
            }

            // 8. Run scorers
            List<com.clawkit.evaluation.scorer.Score> scores = new ArrayList<>();
            for (var scorer : spec.scorers()) {
                try {
                    scores.add(scorer.score(spec,
                        new BenchmarkResult(spec.id(), "", spec.category(), true,
                            BenchmarkResult.FailureCategory.NONE, output, metrics,
                            List.of(), workDir, runIds, durationMs, null),
                        workDir));
                } catch (Exception e) {
                    scores.add(com.clawkit.evaluation.scorer.Score.fail(
                        scorer.getClass().getSimpleName(), "exception", e.getMessage(),
                        e.getMessage() != null ? e.getMessage() : "scorer error"));
                }
            }

            // 9. Determine pass/fail
            boolean passed = scores.stream()
                .noneMatch(s -> s.status() == com.clawkit.evaluation.scorer.ScoreStatus.FAIL);
            var failureCat = passed ? BenchmarkResult.FailureCategory.NONE
                : BenchmarkResult.FailureCategory.TASK_FAILED;

            // 10. Cleanup on success
            if (passed) {
                fixtureMgr.cleanup(workDir);
            }

            return new BenchmarkResult(spec.id(), spec.prompt(), spec.category(),
                passed, failureCat, output, metrics, scores,
                passed ? null : workDir, runIds, durationMs, null);

        } catch (Exception e) {
            log.error("Infrastructure error running {}: {}", spec.id(), e.getMessage(), e);
            return new BenchmarkResult(spec.id(), spec.prompt(), spec.category(),
                false, BenchmarkResult.FailureCategory.INFRASTRUCTURE_ERROR,
                "", null, List.of(), caseOutputDir, runIds,
                System.currentTimeMillis() - startMs, e);
        }
    }

    /** 运行全部 case */
    public BenchmarkReport runAll(List<BenchmarkSpec> cases) {
        long startMs = System.currentTimeMillis();
        List<BenchmarkResult> results = new ArrayList<>();

        for (var spec : cases) {
            log.info("Running benchmark: {}", spec.id());
            results.add(runCase(spec));
        }

        // Determine overall verdict
        boolean anyFailed = results.stream().anyMatch(r -> !r.passed());
        String verdict = anyFailed ? "DEGRADED" : "UNCHANGED";

        return new BenchmarkReport(evaluationId, "0.1.0",
            gitCommit(), startMs, System.currentTimeMillis() - startMs,
            results, verdict, null);
    }

    private static ToolRegistry buildRegistry(BenchmarkSpec spec, Path workDir) {
        var registry = new ToolRegistry();
        // Register real tools for all cases
        registry.register(new com.clawkit.tools.impl.ReadTool(workDir));
        registry.register(new com.clawkit.tools.impl.WriteTool(workDir));
        registry.register(new com.clawkit.tools.impl.EditTool(workDir));
        registry.register(new com.clawkit.tools.impl.GlobTool(workDir));
        registry.register(new com.clawkit.tools.impl.GrepTool(workDir));
        registry.register(new com.clawkit.tools.impl.BashTool(workDir));
        registry.register(new com.clawkit.tools.impl.TodoWriteTool());
        registry.addInterceptor(new com.clawkit.tools.impl.CommandSafetyInterceptor());
        return registry;
    }

    private static String gitCommit() {
        try {
            var process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .redirectErrorStream(true)
                .start();
            var out = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return out.isEmpty() ? "unknown" : out;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
