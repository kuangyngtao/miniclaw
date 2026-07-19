package com.clawkit.observability;

/**
 * 工具重试调度事件 payload（P1-A2）。
 * 在每次新增 attempt 前发出。
 */
public record ToolRetryScheduledPayload(
    String toolCallId,
    String toolName,
    int attemptNumber,
    int maxAttempts,
    long delayMs,
    String failureClass,
    String retryReason
) implements RunEventPayload {
}
