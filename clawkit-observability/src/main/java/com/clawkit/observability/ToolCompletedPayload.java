package com.clawkit.observability;

/**
 * 工具调用完成事件 payload。
 * 不保存完整工具输出。
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
    String errorMessage
) implements RunEventPayload {
}
