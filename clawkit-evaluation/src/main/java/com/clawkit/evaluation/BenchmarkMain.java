package com.clawkit.evaluation;

import com.clawkit.evaluation.baseline.BaselineData;
import com.clawkit.evaluation.baseline.BaselineStore;
import com.clawkit.evaluation.baseline.RegressionComparator;
import com.clawkit.evaluation.baseline.Verdict;
import com.clawkit.evaluation.report.ConsoleReporter;

import java.nio.file.Path;
import java.util.List;

/**
 * CLI 入口。
 *
 * <pre>{@code
 * mvn -pl clawkit-evaluation -am -Pbenchmark verify -Dbenchmark.mode=run
 * mvn -pl clawkit-evaluation -am -Pbenchmark verify -Dbenchmark.mode=compare
 * mvn -pl clawkit-evaluation -am -Pbenchmark verify "-Dexec.args=baseline"
 * }</pre>
 *
 * <p>安全语义：
 * <ul>
 *   <li>{@code run} — 运行全部 case，写报告到 target/，<b>永不</b>写入或覆盖 baseline。</li>
 *   <li>{@code compare} — 要求 baseline 已存在（否则退出非零），运行后对比，
 *       退化时退出非零。不覆盖 baseline。</li>
 *   <li>{@code baseline --output <candidate>} — 生成 candidate，
 *       默认输出 runtime-v1.candidate.json。<b>禁止</b>直接覆盖正式 baseline。</li>
 * </ul>
 */
public class BenchmarkMain {

    private static final Path DEFAULT_BASELINE = Path.of(
        "benchmarks/baselines/runtime-v1.json");
    private static final Path OUTPUT_DIR = Path.of("target/benchmark-runs");
    private static final Path REPORT_DIR = Path.of("target/benchmark-reports");

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "run";
        String mode = System.getProperty("benchmark.mode", "run");
        String baselinePath = System.getProperty("benchmark.baseline",
            DEFAULT_BASELINE.toString());

        switch (command) {
            case "run" -> runBenchmark(mode, Path.of(baselinePath));
            case "baseline" -> generateBaseline(args);
            case "list" -> listCases();
            default -> {
                System.err.println("Usage: benchmark <run|baseline|list>");
                System.exit(1);
            }
        }
    }

    private static void runBenchmark(String mode, Path baselinePath) throws Exception {
        var specs = BenchmarkCatalog.allCases();
        var runner = new BenchmarkRunner(OUTPUT_DIR);
        var report = runner.runAll(specs);
        ConsoleReporter.printConsole(report);
        ConsoleReporter.writeAll(report,
            REPORT_DIR.resolve(report.evaluationId()));

        if ("compare".equals(mode)) {
            var baseline = BaselineStore.load(baselinePath);
            if (baseline.isEmpty()) {
                System.err.println("ERROR: Baseline not found: " + baselinePath);
                System.err.println("Run 'baseline' command first to generate a candidate,");
                System.err.println("then review and copy it to: " + DEFAULT_BASELINE);
                System.exit(3);
            }
            printRegression(report, specs, baseline.get());
        } else {
            // run mode: NEVER write or overwrite baseline
            if (report.failed() > 0) {
                System.exit(2);
            }
        }
    }

    private static void printRegression(BenchmarkReport report, List<BenchmarkSpec> specs,
                                         BaselineData baseline) {
        var regression = RegressionComparator.compare(report, specs, baseline);
        System.out.println();
        System.out.println("=== Regression Report ===");
        System.out.println("Baseline: " + baseline.createdAt() + " ("
            + baseline.gitCommit() + ")");
        System.out.println("Case fingerprint:    " + baseline.caseSetFingerprint().substring(0, 16) + "...");
        System.out.println("Script fingerprint:  " + baseline.scriptScorerFingerprint().substring(0, 16) + "...");
        System.out.println();

        if (regression.verdict() == Verdict.INCOMPATIBLE_BASELINE) {
            System.err.println("INCOMPATIBLE_BASELINE: " + regression.detail());
            System.exit(4);
        }

        System.out.printf("%-35s %10s %10s %10s %s%n",
            "Metric", "Baseline", "Current", "Delta", "Verdict");
        System.out.println("-".repeat(80));

        for (var d : regression.diffs()) {
            String verdict = switch (d.verdict()) {
                case DEGRADED -> "DEGRADED";
                case IMPROVED -> "IMPROVED";
                case UNCHANGED -> "UNCHANGED";
                default -> "?";
            };
            System.out.printf("%-35s %10s %10s %10s %s%n",
                d.metric(), d.baseline(), d.current(), d.delta(), verdict);
        }

        System.out.println();
        System.out.printf("Overall: %s  (%d degraded, %d improved)%n",
            regression.verdict(), regression.degradedCount(), regression.improvedCount());

        if (regression.hasDegradation()) {
            System.exit(2);
        }
    }

    private static void generateBaseline(String[] args) throws Exception {
        String outputPath = DEFAULT_BASELINE.toString()
            .replace(".json", ".candidate.json");
        for (int i = 1; i < args.length; i++) {
            if ("--output".equals(args[i]) && i + 1 < args.length) {
                outputPath = args[i + 1];
            }
        }

        // Safety: refuse to write directly to the formal baseline
        Path formal = DEFAULT_BASELINE.toAbsolutePath().normalize();
        Path output = Path.of(outputPath).toAbsolutePath().normalize();
        if (output.equals(formal)) {
            System.err.println("ERROR: Refusing to overwrite formal baseline.");
            System.err.println("  Formal:  " + formal);
            System.err.println("  Use --output to write a candidate, then review and copy.");
            System.exit(5);
        }

        var specs = BenchmarkCatalog.allCases();
        var runner = new BenchmarkRunner(OUTPUT_DIR);
        var report = runner.runAll(specs);
        ConsoleReporter.printConsole(report);

        var baseline = BaselineData.from(report, specs, report.gitCommit());
        BaselineStore.save(output, baseline);
        System.out.println("Candidate baseline written to: " + output);
        System.out.println("Review diff and copy to: " + formal);
        System.out.println("  Case fingerprint:    " + baseline.caseSetFingerprint().substring(0, 16) + "...");
        System.out.println("  Script fingerprint:  " + baseline.scriptScorerFingerprint().substring(0, 16) + "...");
    }

    private static void listCases() {
        var specs = BenchmarkCatalog.allCases();
        System.out.println("Available benchmark cases (" + specs.size() + "):");
        for (var spec : specs) {
            System.out.printf("  %-25s %-15s %s%n",
                spec.id(), spec.category(),
                String.join(", ", spec.tags()));
        }
    }
}
