package com.clawkit.evaluation.report;

import com.clawkit.evaluation.BenchmarkReport;
import com.clawkit.evaluation.BenchmarkResult;
import com.clawkit.evaluation.scorer.ScoreStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 基准测试报告输出：Console / JSON / Markdown。
 */
public class ConsoleReporter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String RESET = "[0m";
    private static final String GREEN = "[32m";
    private static final String RED = "[31m";
    private static final String YELLOW = "[33m";
    private static final String BOLD = "[1m";
    private static final String GRAY = "[90m";

    /** 打印控制台报告 */
    public static void printConsole(BenchmarkReport report) {
        System.out.println();
        System.out.println(BOLD + "=== clawkit Benchmark Report ===" + RESET);
        System.out.println("Run:    " + Instant.now());
        System.out.println("Version:" + report.clawkitVersion());
        System.out.println("Commit: " + report.gitCommit());
        System.out.println();

        printSummary(report);
        printPerCase(report);
    }

    private static void printSummary(BenchmarkReport report) {
        System.out.println(BOLD + "Summary:" + RESET);
        System.out.printf("  Total:    %d/%d passed (%.1f%%)%n",
            report.passed(), report.totalCases(), report.passRate() * 100);
        System.out.printf("  Avg turns:      %.1f%n", report.avgTurns());
        System.out.printf("  Avg tools:      %.1f%n", report.avgToolCalls());
        System.out.printf("  Avg duration:   %.0fms%n", report.avgDurationMs());
        System.out.printf("  Tool fail rate: %.1f%%%n", report.toolFailureRate() * 100);
        System.out.printf("  Compactions:    %d%n", report.totalCompactions());
        System.out.printf("  Provider calls: %.1f avg%n", report.avgProviderCalls());
        System.out.println();
    }

    private static void printPerCase(BenchmarkReport report) {
        System.out.println(BOLD + "Per Case:" + RESET);
        for (var r : report.results()) {
            String status = r.passed() ? GREEN + "PASS" + RESET : RED + "FAIL" + RESET;
            System.out.printf("  %-25s %s  %d turns, %d tools, %dms, %d failures%n",
                r.caseId(), status, r.turns(), r.toolCalls(),
                r.durationMs(), r.toolFailures());
        }
        System.out.println();
    }

    /** 写入 JSON 报告 */
    public static void writeJson(BenchmarkReport report, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        MAPPER.writeValue(outputPath.toFile(), report);
    }

    /** 写入 Markdown 报告 */
    public static void writeMarkdown(BenchmarkReport report, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        var sb = new StringBuilder();
        sb.append("# clawkit Benchmark Report\n\n");
        sb.append("**Run:** ").append(Instant.now()).append("  \n");
        sb.append("**Version:** ").append(report.clawkitVersion()).append("  \n");
        sb.append("**Commit:** ").append(report.gitCommit()).append("  \n\n");

        sb.append("## Summary\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append(String.format("| Total | %d/%d passed (%.1f%%) |\n",
            report.passed(), report.totalCases(), report.passRate() * 100));
        sb.append(String.format("| Avg turns | %.1f |\n", report.avgTurns()));
        sb.append(String.format("| Avg tools | %.1f |\n", report.avgToolCalls()));
        sb.append(String.format("| Avg duration | %.0fms |\n", report.avgDurationMs()));
        sb.append(String.format("| Tool fail rate | %.1f%% |\n", report.toolFailureRate() * 100));
        sb.append(String.format("| Compactions | %d |\n", report.totalCompactions()));
        sb.append(String.format("| Verdict | **%s** |\n\n", report.verdict()));

        sb.append("## Per Case\n\n");
        sb.append("| Case | Status | Turns | Tools | Duration | Failures |\n");
        sb.append("|------|--------|-------|-------|----------|----------|\n");
        for (var r : report.results()) {
            sb.append(String.format("| %s | %s | %d | %d | %dms | %d |\n",
                r.caseId(), r.passed() ? "PASS" : "FAIL",
                r.turns(), r.toolCalls(), r.durationMs(), r.toolFailures()));
        }

        Files.writeString(outputPath, sb.toString());
    }

    /** 写入完整报告产物 */
    public static void writeAll(BenchmarkReport report, Path reportDir) throws IOException {
        Files.createDirectories(reportDir);
        writeJson(report, reportDir.resolve("report.json"));
        writeMarkdown(report, reportDir.resolve("report.md"));
        // console output is printed to stdout, not saved separately here
    }
}
