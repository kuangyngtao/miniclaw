package com.clawkit.tools;

/**
 * 工具执行的结构化结果，取代旧的字符串输出。
 * 所有工具调用统一通过此 record 返回结果。
 */
public record ToolExecutionResult(
    String toolCallId,
    String toolName,
    String output,
    boolean error,
    String errorCode,
    long durationMs,
    int outputBytes,
    boolean truncated,
    boolean timedOut,
    Integer exitCode,
    ToolMetadata metadata
) {
    /** 成功结果 */
    public static ToolExecutionResult success(
            String toolCallId, String toolName, String output,
            long durationMs, ToolMetadata metadata) {
        return new ToolExecutionResult(toolCallId, toolName, output,
            false, null, durationMs, output.length(), false, false,
            null, metadata);
    }

    /** 错误结果 */
    public static ToolExecutionResult error(
            String toolCallId, String toolName, String errorCode,
            String message, long durationMs, ToolMetadata metadata) {
        return new ToolExecutionResult(toolCallId, toolName, message,
            true, errorCode, durationMs, message.length(), false, false,
            null, metadata);
    }
}
