package com.clawkit.observability;

import java.util.List;

/**
 * 内存中的完整 run 指标投影。
 * 不落盘——从 events 动态计算，供 /metrics 命令使用。
 */
public record RunMetrics(
    String runId,
    RunSummary summary,
    ProviderMetrics provider,
    ToolMetrics tools,
    ContextMetrics context,
    CompactMetrics compact,
    ApprovalMetrics approval,
    List<String> warnings
) {
    public record ProviderMetrics(
        int calls,
        int failures,
        int retries,
        long durationMs,
        long inputTokens,
        long outputTokens,
        boolean tokensEstimated
    ) {}

    public record ToolMetrics(
        int calls,
        int failures,
        int lowRisk,
        int mediumRisk,
        int highRisk
    ) {}

    public record ContextMetrics(
        int peakTokens,
        int contextWindow,
        int compactCount,
        int compactFailures
    ) {}

    public record CompactMetrics(
        int count,
        int failures
    ) {}

    public record ApprovalMetrics(
        int requested,
        int approved,
        int rejected,
        int modified
    ) {}
}
