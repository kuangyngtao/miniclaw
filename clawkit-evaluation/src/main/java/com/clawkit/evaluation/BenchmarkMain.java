package com.clawkit.evaluation;

import com.clawkit.evaluation.baseline.BaselineData;
import com.clawkit.evaluation.baseline.BaselineStore;
import com.clawkit.evaluation.baseline.RegressionComparator;
import com.clawkit.evaluation.baseline.Verdict;
import com.clawkit.evaluation.report.ConsoleReporter;

import java.nio.file.Path;

/**
 * CLI 入口。
 *
 * <pre>{@code
 * mvn -pl clawkit-evaluation -am -Pbenchmark verify
 * mvn -pl clawkit-evaluation -am exec:java -Dexec.mainClass=... -Dexec.args="baseline"
 * }</pre>
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
        var runner = new BenchmarkRunner(OUTPUT_DIR);
        var report = runner.runAll(BenchmarkCatalog.allCases());
        ConsoleReporter.printConsole(report);
        ConsoleReporter.writeAll(report,
            REPORT_DIR.resolve(report.evaluationId()));

        if ("compare".equals(mode)) {
            var baseline = BaselineStore.load(baselinePath);
            if (baseline.isEmpty()) {
                System.err.println("Baseline not found: " + baselinePath);
                System.exit(3);
            }
            printRegression(report, baseline.get());

            // Save new baseline as candidate
            var candidatePath = Path.of(
                baselinePath.toString().replace(".json", ".candidate.json"));
            var newBaseline = BaselineData.from(report, report.gitCommit());
            BaselineStore.save(candidatePath, newBaseline);
            System.out.println("Candidate baseline: " + candidatePath);
        } else {
            // Just save baseline
            var newBaseline = BaselineData.from(report, report.gitCommit());
            BaselineStore.save(baselinePath, newBaseline);
        }

        // Exit codes
        if (report.failed() > 0) {
            System.exit(2);
        }
    }

    private static void printRegression(BenchmarkReport report, BaselineData baseline) {
        var regression = RegressionComparator.compare(report, baseline);
        System.out.println();
        System.out.println("=== Regression Report ===");
        System.out.println("Baseline: " + baseline.createdAt() + " (" + baseline.gitCommit() + ")");
        System.out.println("Fingerprint: " + baseline.caseSetHash());
        System.out.println();
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
        String outputPath = "benchmarks/baselines/runtime-v1.candidate.json";
        for (int i = 1; i < args.length; i++) {
            if ("--output".equals(args[i]) && i + 1 < args.length) {
                outputPath = args[i + 1];
            }
        }

        var runner = new BenchmarkRunner(OUTPUT_DIR);
        var report = runner.runAll(BenchmarkCatalog.allCases());
        ConsoleReporter.printConsole(report);

        var baseline = BaselineData.from(report, report.gitCommit());
        BaselineStore.save(Path.of(outputPath), baseline);
        System.out.println("Candidate baseline written to: " + outputPath);
        System.out.println("Review diff and replace benchmarks/baselines/runtime-v1.json");
    }

    private static void listCases() {
        System.out.println("Available benchmark cases (" + BenchmarkCatalog.allCases().size() + "):");
        for (var spec : BenchmarkCatalog.allCases()) {
            System.out.printf("  %-25s %-15s %s%n",
                spec.id(), spec.category(),
                String.join(", ", spec.tags()));
        }
    }
}
