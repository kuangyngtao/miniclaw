package com.clawkit.observability.model;

/**
 * 单次工具调用指标。
 */
public record ToolCallMetrics(
    String runId,
    int turnNumber,
    String toolCallId,
    String toolName,
    String argSummary,
    boolean readOnly,
    boolean isInternal,
    boolean approved,
    String approvalDecision,
    boolean success,
    long durationMs,
    int outputBytes,
    boolean truncated,
    String errorCode,
    String errorMessage
) {}
