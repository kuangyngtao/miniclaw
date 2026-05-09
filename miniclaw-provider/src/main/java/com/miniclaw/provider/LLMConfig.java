package com.miniclaw.provider;

import java.time.Duration;

/**
 * LLM 客户端配置 — 超时 + 重试参数。
 * 不可变 record，通过 Builder 模式创建。
 */
public record LLMConfig(
    String apiKey,
    String baseUrl,
    String model,
    Duration connectTimeout,
    Duration requestTimeout,
    int maxRetries
) {
    public static Builder builder() {
        return new Builder();
    }

    public static LLMConfig defaults(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    public static class Builder {
        private String apiKey;
        private String baseUrl = "https://api.anthropic.com/v1";
        private String model = "claude-sonnet-4-6";
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(60);
        private int maxRetries = 3;

        public Builder apiKey(String apiKey)              { this.apiKey = apiKey; return this; }
        public Builder baseUrl(String baseUrl)            { this.baseUrl = baseUrl; return this; }
        public Builder model(String model)                { this.model = model; return this; }
        public Builder connectTimeout(Duration timeout)   { this.connectTimeout = timeout; return this; }
        public Builder requestTimeout(Duration timeout)   { this.requestTimeout = timeout; return this; }
        public Builder maxRetries(int maxRetries)         { this.maxRetries = maxRetries; return this; }

        public LLMConfig build() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("apiKey must not be blank");
            }
            return new LLMConfig(apiKey, baseUrl, model, connectTimeout, requestTimeout, maxRetries);
        }
    }
}
