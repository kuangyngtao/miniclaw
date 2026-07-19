package com.clawkit.provider;

/**
 * LLM 调用失败异常 — 超时/网络/API 错误均在重试耗尽后抛出。
 * 对应错误码 A-002: LLM 调用失败。
 */
public class LLMException extends RuntimeException {
    private final ProviderError providerError;
    private final int retryCount;

    public LLMException(String message) {
        this(message, null, null, 0);
    }

    public LLMException(String message, Throwable cause) {
        this(message, cause, null, 0);
    }

    public LLMException(String message, ProviderError providerError) {
        this(message, null, providerError, 0);
    }

    public LLMException(String message, Throwable cause, ProviderError providerError) {
        this(message, cause, providerError, 0);
    }

    /** P1-A3：携带真实 retryCount */
    public LLMException(String message, Throwable cause, ProviderError providerError, int retryCount) {
        super(message, cause);
        this.providerError = providerError;
        this.retryCount = retryCount;
    }

    public ProviderError providerError() { return providerError; }

    /** P1-A3：实际已发生的 Provider transport 重试次数 */
    public int retryCount() { return retryCount; }
}
