package com.clawkit.observability;

/** turn 完成事件 payload。 */
public record TurnCompletedPayload(
    int toolCallCount,
    boolean hasFinalReply,
    boolean wasRetry,
    String errorCode,
    String errorMessage
) implements RunEventPayload {
}
