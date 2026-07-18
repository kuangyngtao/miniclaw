package com.clawkit.provider;

/** 模型完成原因。 */
public enum FinishReason {
    STOP,
    TOOL_CALLS,
    LENGTH,
    CONTENT_FILTER,
    CANCELLED,
    ERROR,
    UNKNOWN
}
