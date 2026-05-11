package com.miniclaw.tools;

import com.miniclaw.tools.schema.ToolCall;

/**
 * 安全拦截器 — 在工具执行前检查调用是否安全。
 * 返回 null 表示通过，返回非 null 字符串为拦截原因。
 */
@FunctionalInterface
public interface SafetyInterceptor {
    String check(ToolCall call);
}
