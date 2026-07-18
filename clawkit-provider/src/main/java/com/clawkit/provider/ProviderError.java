package com.clawkit.provider;

import java.time.Duration;

/** 结构化 Provider 错误。 */
public sealed interface ProviderError {
    record Authentication(String message) implements ProviderError {}
    record RateLimited(String message, Duration retryAfter) implements ProviderError {}
    record Timeout(String message) implements ProviderError {}
    record ContextLengthExceeded(String message) implements ProviderError {}
    record Protocol(String message) implements ProviderError {}
    record Server(String message, int statusCode) implements ProviderError {}
    record Network(String message) implements ProviderError {}
    record Cancelled() implements ProviderError {}
}
