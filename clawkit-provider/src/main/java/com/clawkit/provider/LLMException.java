package com.clawkit.provider;

/**
 * LLM 调用失败异常 — 超时/网络/API 错误均在重试耗尽后抛出。
 * 对应错误码 A-002: LLM 调用失败。
 */
public class LLMException extends RuntimeException {
    private final ProviderError providerError;

    public LLMException(String message) {
        this(message, null, null);
    }

    public LLMException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public LLMException(String message, ProviderError providerError) {
        this(message, null, providerError);
    }

    public LLMException(String message, Throwable cause, ProviderError providerError) {
        super(message, cause);
        this.providerError = providerError;
    }

    public ProviderError providerError() { return providerError; }
}
