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
    String errorMessage,
    String actualModel,
    int promptCacheHitTokens,
    int promptCacheMissTokens,
    int reasoningTokens,
    String usageSource
) implements RunEventPayload {
    public ProviderCallCompletedPayload(String providerCallId, String phase, boolean streaming,
                                        int inputTokens, int outputTokens, boolean tokensEstimated,
                                        long durationMs, int retryCount, boolean failed,
                                        String errorCode, String errorMessage) {
        this(providerCallId, phase, streaming, inputTokens, outputTokens, tokensEstimated,
            durationMs, retryCount, failed, errorCode, errorMessage, null, 0, 0, 0,
            tokensEstimated ? "ESTIMATED" : "UNAVAILABLE");
    }
}
