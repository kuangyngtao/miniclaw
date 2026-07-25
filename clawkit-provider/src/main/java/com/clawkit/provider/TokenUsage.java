package com.clawkit.provider;

/** Token 用量记录。 */
public record TokenUsage(
    int promptTokens,
    int completionTokens,
    int totalTokens,
    int promptCacheHitTokens,
    int promptCacheMissTokens,
    int reasoningTokens,
    UsageSource source
) {
    public static final TokenUsage EMPTY = new TokenUsage(
        0, 0, 0, 0, 0, 0, UsageSource.UNAVAILABLE);

    public TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
        this(promptTokens, completionTokens, totalTokens, 0, promptTokens, 0, UsageSource.ACTUAL);
    }

    public TokenUsage {
        if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0
            || promptCacheHitTokens < 0 || promptCacheMissTokens < 0 || reasoningTokens < 0) {
            throw new IllegalArgumentException("token usage values must be non-negative");
        }
        if (source == null) source = UsageSource.UNAVAILABLE;
        if (source == UsageSource.ACTUAL
            && promptCacheHitTokens + promptCacheMissTokens > 0
            && promptTokens != promptCacheHitTokens + promptCacheMissTokens) {
            throw new IllegalArgumentException(
                "actual prompt tokens must equal cache hit plus cache miss tokens");
        }
    }
}
