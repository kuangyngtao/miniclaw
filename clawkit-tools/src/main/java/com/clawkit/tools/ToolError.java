package com.clawkit.tools;

import java.util.Map;

/**
 * 结构化工具错误。
 * 原始 Throwable 不进入此 record；持久化前必须转换为安全摘要。
 */
public record ToolError(
    String code,
    String message,
    boolean retryable,
    Map<String, String> details
) {
    /** 从异常创建非可重试错误 */
    public static ToolError fromThrowable(Throwable t) {
        return new ToolError(
            "INTERNAL_ERROR",
            t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName(),
            false,
            Map.of("exceptionType", t.getClass().getName())
        );
    }

    /** 创建可重试错误 */
    public static ToolError retryable(String code, String message) {
        return new ToolError(code, message, true, Map.of());
    }

    /** 创建不可重试错误 */
    public static ToolError fatal(String code, String message) {
        return new ToolError(code, message, false, Map.of());
    }
}
