package com.miniclaw.provider;

/**
 * LLM 调用失败异常 — 超时/网络/API 错误均在重试耗尽后抛出。
 * 对应错误码 A-002: LLM 调用失败。
 */
public class LLMException extends RuntimeException {

    public LLMException(String message) {
        super(message);
    }

    public LLMException(String message, Throwable cause) {
        super(message, cause);
    }
}
