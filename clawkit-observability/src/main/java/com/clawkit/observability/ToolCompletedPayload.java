package com.clawkit.observability;

/**
 * 工具调用完成事件 payload。
 * 不保存完整工具输出。
 *
 * <p>P1-A2 兼容增加 attemptCount、failureClass、recoveryDirective、finalStopReason。
 * 旧 reader 按默认值 attemptCount=1 解析。
 */
public record ToolCompletedPayload(
    String toolCallId,
    String toolName,
    boolean success,
    long durationMs,
    int outputBytes,
    boolean truncated,
    boolean timedOut,
    Integer exitCode,
    String errorCode,
    String errorMessage,
    // ── P1-A2 兼容字段 ──────────────────────────────────────────────
    int attemptCount,
    String failureClassName,
    String recoveryDirectiveName,
    String finalStopReason,
    // ── P1-A4 兼容字段 ──────────────────────────────────────────────
    long totalSourceBytes,
    long retainedSourceBytes,
    long returnedOutputBytes,
    long totalLines,
    long returnedLines,
    String truncationReason,
    String retentionPolicy,
    boolean inputComplete
) implements RunEventPayload {

    /** 旧构造器兼容：attemptCount=1 */
    public ToolCompletedPayload(
        String toolCallId,
        String toolName,
        boolean success,
        long durationMs,
        int outputBytes,
        boolean truncated,
        boolean timedOut,
        Integer exitCode,
        String errorCode,
        String errorMessage
    ) {
        this(toolCallId, toolName, success, durationMs, outputBytes, truncated,
            timedOut, exitCode, errorCode, errorMessage,
            1, null, null, null,
            outputBytes, outputBytes, outputBytes, -1, -1,
            truncated ? "MAX_OUTPUT_BYTES" : null, "LEGACY_V0", !truncated);
    }
}
