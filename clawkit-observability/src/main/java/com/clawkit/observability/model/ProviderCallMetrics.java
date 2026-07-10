package com.clawkit.observability.model;

/**
 * 单次 LLM Provider 调用指标。
 */
public record ProviderCallMetrics(
    String runId,
    int turnNumber,
    /** 调用阶段: phase1 / phase2 / compact / extract */
    String phase,
    boolean streaming,
    int inputTokens,
    int outputTokens,
    boolean tokensEstimated,
    long durationMs,
    /** 本次调用中的重试次数（0 = 首次成功） */
    int retryCount,
    /** 是否最终失败 */
    boolean failed,
    String errorCode,
    String errorMessage
) {}
