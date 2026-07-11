package com.clawkit.evaluation.baseline;

import com.clawkit.evaluation.BenchmarkReport;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * 版本化基线数据结构，持久化到 JSON。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BaselineData(
    int schemaVersion,
    String suiteId,
    String suiteVersion,
    int metricSchemaVersion,
    String executionProfile,
    String caseSetHash,
    String scriptSetHash,
    Instant createdAt,
    String clawkitVersion,
    String gitCommit,
    String javaVersion,
    String os,
    Summary summary,
    Map<String, CaseEntry> cases
) {
    public record Summary(
        int totalCases,
        int passed,
        int failed,
        double avgTurns,
        double avgToolCalls,
        double avgDurationMs,
        double avgToolFailures,
        double toolFailureRate,
        double avgProviderCalls,
        double avgProviderRetries,
        int totalCompactions,
        double avgInputTokens,
        double avgOutputTokens
    ) {}

    public record CaseEntry(
        boolean passed,
        int turns,
        int toolCalls,
        int toolFailures,
        long durationMs,
        int providerCalls,
        int providerRetries,
        int compactions,
        int highRiskTools,
        int mediumRiskTools,
        int lowRiskTools,
        int approvalRequested,
        int approvalApproved,
        int approvalRejected,
        String permissionMode,
        String thinkingMode,
        String status
    ) {}

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static BaselineData from(BenchmarkReport report, String gitCommit) {
        var cases = new java.util.LinkedHashMap<String, CaseEntry>();
        for (var r : report.results()) {
            var s = r.summary();
            cases.put(r.caseId(), new CaseEntry(
                r.passed(), r.turns(), r.toolCalls(), r.toolFailures(),
                r.durationMs(), r.providerCalls(), r.providerRetries(),
                r.compactCount(),
                r.metrics() != null ? r.metrics().tools().highRisk() : 0,
                r.metrics() != null ? r.metrics().tools().mediumRisk() : 0,
                r.metrics() != null ? r.metrics().tools().lowRisk() : 0,
                r.metrics() != null ? r.metrics().approval().requested() : 0,
                r.metrics() != null ? r.metrics().approval().approved() : 0,
                r.metrics() != null ? r.metrics().approval().rejected() : 0,
                s != null ? s.permissionMode() : "",
                s != null ? s.thinkingMode() : "",
                s != null ? s.status().name() : ""
            ));
        }
        return new BaselineData(
            CURRENT_SCHEMA_VERSION,
            "clawkit-runtime-regression",
            "1.0",
            1,
            "default",
            hashCases(report),
            hashScripts(report),
            Instant.now(),
            report.clawkitVersion(),
            gitCommit,
            System.getProperty("java.version"),
            System.getProperty("os.name"),
            new Summary(
                report.totalCases(), report.passed(), report.failed(),
                report.avgTurns(), report.avgToolCalls(), report.avgDurationMs(),
                report.avgToolFailures(), report.toolFailureRate(),
                report.avgProviderCalls(), report.avgProviderRetries(),
                report.totalCompactions(), report.avgInputTokens(), report.avgOutputTokens()
            ),
            cases
        );
    }

    private static String hashCases(BenchmarkReport report) {
        var sb = new StringBuilder();
        for (var r : report.results()) sb.append(r.caseId()).append("|");
        return Integer.toHexString(sb.toString().hashCode());
    }

    private static String hashScripts(BenchmarkReport report) {
        return Integer.toHexString(report.results().hashCode());
    }
}
