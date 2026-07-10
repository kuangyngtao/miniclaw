package com.clawkit.context.impl;

import com.clawkit.context.Tokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tokenizer 工厂 — 先尝试 JTokkit，不可用时降级到字符估算。
 */
public final class TokenizerFactory {

    private static final Logger log = LoggerFactory.getLogger(TokenizerFactory.class);

    private TokenizerFactory() {}

    /**
     * 创建 Tokenizer。优先 JTokkit，失败时降级到字符估算（带 WARN 日志）。
     *
     * @param encodingName 编码名称，例如 "cl100k_base"、"o200k_base"
     */
    public static Tokenizer create(String encodingName) {
        try {
            return new TiktokenTokenizer(encodingName);
        } catch (Exception e) {
            log.warn("[Tokenizer] JTokkit 不可用 ({}), 降级到字符估算", e.getMessage());
            return new CharFallbackTokenizer();
        }
    }
}
