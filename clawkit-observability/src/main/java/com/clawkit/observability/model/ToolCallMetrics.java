package com.clawkit.observability.model;

import com.clawkit.tools.ToolExecutionResult;

/**
 * 单次工具调用指标。
 * 从 ToolExecutionResult 派生，作为观测层投影。
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
) {
    /** 从 ToolExecutionResult 派生（观测层投影） */
    public static ToolCallMetrics from(ToolExecutionResult r, String runId, int turn,
                                        String argSummary, boolean readOnly, boolean isInternal) {
        return new ToolCallMetrics(runId, turn, r.toolCallId(), r.toolName(), argSummary,
            readOnly, isInternal, false, null, !r.error(), r.durationMs(),
            r.outputBytes(), r.truncated(), r.errorCode(), r.output());
    }
}
