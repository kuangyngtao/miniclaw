package com.clawkit.provider;

import com.clawkit.provider.impl.openai.OpenAIProvider;

/**
 * 根据 {@link LLMConfig#protocol()} 创建对应的 {@link LLMProvider} 实例。
 * 新增协议只需在此加一个分支。
 */
public final class ProviderFactory {

    private ProviderFactory() {}

    public static LLMProvider create(LLMConfig config) {
        return switch (config.protocol()) {
            case OPENAI_COMPAT -> new OpenAIProvider(config);
            case ANTHROPIC -> throw new UnsupportedOperationException(
                "Anthropic 原生协议尚未实现，请使用 OPENAI_COMPAT 协议");
        };
    }
}
