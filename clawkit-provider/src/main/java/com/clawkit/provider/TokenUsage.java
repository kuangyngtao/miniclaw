package com.clawkit.provider;

/** Token 用量记录。 */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
    public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0);
}
