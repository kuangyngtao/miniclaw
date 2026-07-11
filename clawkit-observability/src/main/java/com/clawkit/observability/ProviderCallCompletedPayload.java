package com.clawkit.observability;

/**
 * Provider 调用完成事件 payload。
 * phase 取值：phase1 / phase2 / plan / compact / memory_extract。
 */
public record ProviderCallCompletedPayload(
    String providerCallId,
    String phase,
    boolean streaming,
    int inputTokens,
    int outputTokens,
    boolean tokensEstimated,
    long durationMs,
    int retryCount,
    boolean failed,
    String errorCode,
    String errorMessage
) implements RunEventPayload {
}
