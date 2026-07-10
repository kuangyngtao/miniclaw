package com.clawkit.provider;

import java.time.Duration;

/**
 * LLM 客户端配置 — 超时 + 重试参数。
 * 不可变 record，通过 Builder 模式创建。
 */
public record LLMConfig(
    String apiKey,
    String baseUrl,
    String model,
    Protocol protocol,
    Duration connectTimeout,
    Duration requestTimeout,
    int maxRetries,
    int contextWindow,
    String encoding
) {
    public enum Protocol {
        OPENAI_COMPAT,
        ANTHROPIC
    }
    public static Builder builder() {
        return new Builder();
    }

    public static LLMConfig defaults(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    public static class Builder {
        private String apiKey;
        private String baseUrl = "https://api.deepseek.com";
        private String model = "deepseek-chat";
        private Protocol protocol = Protocol.OPENAI_COMPAT;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(60);
        private int maxRetries = 3;
        private int contextWindow;
        private String encoding;

        public Builder apiKey(String apiKey)              { this.apiKey = apiKey; return this; }
        public Builder baseUrl(String baseUrl)            { this.baseUrl = baseUrl; return this; }
        public Builder model(String model)                { this.model = model; return this; }
        public Builder protocol(Protocol protocol)        { this.protocol = protocol; return this; }
        public Builder connectTimeout(Duration timeout)   { this.connectTimeout = timeout; return this; }
        public Builder requestTimeout(Duration timeout)   { this.requestTimeout = timeout; return this; }
        public Builder maxRetries(int maxRetries)         { this.maxRetries = maxRetries; return this; }
        public Builder contextWindow(int contextWindow)   { this.contextWindow = contextWindow; return this; }
        public Builder encoding(String encoding)          { this.encoding = encoding; return this; }

        public LLMConfig build() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("apiKey must not be blank");
            }
            if (contextWindow <= 0) {
                contextWindow = detectContextWindow(model);
            }
            if (encoding == null || encoding.isBlank()) {
                encoding = detectEncoding(model);
            }
            return new LLMConfig(apiKey, baseUrl, model, protocol,
                connectTimeout, requestTimeout, maxRetries, contextWindow, encoding);
        }

        /** 按模型名前缀匹配上下文窗口大小。未匹配到返回 128000。 */
        private static int detectContextWindow(String model) {
            if (model == null) return 128_000;
            String m = model.toLowerCase();
            if (m.contains("deepseek-v4") || m.contains("deepseek-v4-pro")) return 1_000_000;
            if (m.contains("deepseek")) return 128_000;
            if (m.contains("gpt-4o") || m.contains("o1") || m.contains("o3")) return 200_000;
            if (m.contains("gpt-4")) return 128_000;
            if (m.contains("claude")) return 200_000;
            return 128_000;
        }

        /** 按模型名前缀匹配 tokenizer 编码。未匹配到返回 cl100k_base。 */
        private static String detectEncoding(String model) {
            if (model == null) return "cl100k_base";
            String m = model.toLowerCase();
            if (m.contains("gpt-4o") || m.contains("o1") || m.contains("o3")) return "o200k_base";
            return "cl100k_base";
        }
    }
}
