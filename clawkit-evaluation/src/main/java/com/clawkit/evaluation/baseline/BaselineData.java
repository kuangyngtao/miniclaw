package com.clawkit.evaluation.baseline;

import com.clawkit.evaluation.BenchmarkReport;
import com.clawkit.evaluation.BenchmarkSpec;
import com.clawkit.evaluation.pricing.PricingSnapshot;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 版本化基线数据结构，持久化到 JSON。
 *
 * <p>指纹使用 SHA-256，覆盖 suite 身份、case 元数据、script 步骤和 scorer 描述。
 * 不保存完整 prompt、凭据或敏感内容。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BaselineData(
    int schemaVersion,
    String suiteId,
    String suiteVersion,
    int metricSchemaVersion,
    String executionProfile,
    String caseSetFingerprint,
    String scriptScorerFingerprint,
    String pricingSnapshotVersion,
    String pricingSnapshotHash,
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

    public static final int CURRENT_SCHEMA_VERSION = 3;

    /** 从 report + specs 构建基线数据。specs 用于生成稳定指纹。 */
    public static BaselineData from(BenchmarkReport report, List<BenchmarkSpec> specs,
                                     String gitCommit) {
        return from(report, specs, gitCommit, null);
    }

    public static BaselineData from(BenchmarkReport report, List<BenchmarkSpec> specs,
                                     String gitCommit, PricingSnapshot pricingSnapshot) {
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
            3,
            "default",
            computeCaseSetFingerprint(specs),
            computeScriptScorerFingerprint(specs),
            pricingSnapshot != null ? pricingSnapshot.version() : null,
            pricingSnapshot != null ? pricingSnapshot.hash() : null,
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

    // ═══════════════════════════════════════════════════════════════
    // SHA-256 Fingerprints
    // ═══════════════════════════════════════════════════════════════

    /**
     * Case 集合指纹：suite 身份 + 排序后的 case ID / 类别 / fixture / 权限 / 思维模式。
     */
    static String computeCaseSetFingerprint(List<BenchmarkSpec> specs) {
        var sb = new StringBuilder();
        sb.append("suite=clawkit-runtime-regression|v1.0\n");
        sb.append("metricSchema=v3\n");
        var sorted = specs.stream()
            .sorted(java.util.Comparator.comparing(BenchmarkSpec::id))
            .toList();
        for (var spec : sorted) {
            sb.append("case:").append(spec.id())
                .append("|cat=").append(spec.category())
                .append("|fixture=").append(spec.fixture().name())
                .append("|perm=").append(spec.permissionMode().name())
                .append("|think=").append(spec.thinkingMode().name())
                .append("|exec=").append(spec.executionMode().name())
                .append("\n");
        }
        return sha256(sb.toString());
    }

    /**
     * Script + Scorer 指纹：排序后的 prompt 摘要、script step 语义字段、scorer 描述。
     */
    static String computeScriptScorerFingerprint(List<BenchmarkSpec> specs) {
        var sb = new StringBuilder();
        var sorted = specs.stream()
            .sorted(java.util.Comparator.comparing(BenchmarkSpec::id))
            .toList();
        for (var spec : sorted) {
            sb.append("case:").append(spec.id()).append("\n");
            // Prompt: hash only, never store content
            sb.append("  promptHash:").append(sha256(spec.prompt())).append("\n");
            // Script steps: phase + turn + streaming + tools + response text + error
            for (int i = 0; i < spec.script().size(); i++) {
                var step = spec.script().get(i);
                sb.append("  step:").append(i)
                    .append("|phase=").append(step.phase())
                    .append("|turn=").append(step.expectedTurn())
                    .append("|stream=").append(step.expectedStreaming());
                if (step.expectedAvailableTools() != null) {
                    var tools = new java.util.ArrayList<>(step.expectedAvailableTools());
                    java.util.Collections.sort(tools);
                    sb.append("|tools=").append(String.join(",", tools));
                }
                if (step.response() != null) {
                    sb.append("|response=").append(sha256(step.response().toString()));
                }
                if (step.error() != null) {
                    sb.append("|error=").append(step.error().getClass().getSimpleName())
                        .append(":").append(step.error().getMessage());
                }
                sb.append("\n");
            }
            // Scorers: descriptor fingerprint
            for (int i = 0; i < spec.scorers().size(); i++) {
                var scorer = spec.scorers().get(i);
                sb.append("  scorer:").append(i)
                    .append("|").append(scorer.descriptor().fingerprint())
                    .append("\n");
            }
        }
        return sha256(sb.toString());
    }

    static String sha256(String input) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
