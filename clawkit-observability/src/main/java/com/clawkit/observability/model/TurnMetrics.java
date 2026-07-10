package com.clawkit.observability.model;

/**
 * 单轮指标。
 */
public record TurnMetrics(
    String runId,
    int turnNumber,
    String model,
    int inputTokens,
    int outputTokens,
    boolean tokensEstimated,
    long providerDurationMs,
    int toolCallCount,
    boolean hasFinalReply,
    boolean wasRetry,
    String errorCode,
    String errorMessage
) {}
