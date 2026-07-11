package com.clawkit.observability;

import java.util.Map;

/**
 * 上下文组装完成后、Provider 调用前发出。
 * sections 使用固定 key：system / history / workspace / runtime /
 * memory / related_sessions / skills / tools / user。
 */
public record ContextPreparedPayload(
    int messageCount,
    int totalTokens,
    int contextWindow,
    boolean tokensEstimated,
    String budgetStatus,
    Map<String, Integer> sections,
    boolean truncated,
    int truncatedMessages,
    boolean compactRequired
) implements RunEventPayload {
}
