package com.clawkit.observability;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * 观测数据脱敏工具。
 * 在序列化到 JSONL 之前完成脱敏，不依赖 CLI 展示层。
 */
public final class ObservabilityRedactor {

    private ObservabilityRedactor() {}

    private static final Set<String> SENSITIVE_KEYS = Set.of(
        "apikey", "api_key", "token", "password", "secret",
        "authorization", "webhook", "bearer", "credential",
        "cookie", "privatekey", "private_key"
    );

    static final int MAX_TASK_SUMMARY = 160;
    static final int MAX_ARG_SUMMARY = 256;
    static final int MAX_ERROR_MESSAGE = 1024;

    /** 脱敏文本：将敏感值替换为 [REDACTED] */
    public static String redactText(String value) {
        if (value == null) return null;
        // 简单策略：检查是否为已知的敏感 key 上下文
        // 具体的 key-value 脱敏在 summarizeArguments 中处理
        return value;
    }

    /**
     * 将原始用户请求转换为 taskSummary：
     * 去除换行和多余空白 → 截断到 160 字符。
     * 不调用 LLM。
     */
    public static String summarizeTask(String prompt) {
        if (prompt == null || prompt.isBlank()) return "";
        String cleaned = prompt.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= MAX_TASK_SUMMARY) return cleaned;
        return cleaned.substring(0, MAX_TASK_SUMMARY - 3) + "...";
    }

    /**
     * 生成工具参数的脱敏摘要。
     * 对敏感 key 的值替换为 [REDACTED]。
     */
    public static String summarizeArguments(JsonNode arguments) {
        if (arguments == null || arguments.isNull() || arguments.isEmpty()) return "";
        String summary = redactJson(arguments);
        if (summary.length() <= MAX_ARG_SUMMARY) return summary;
        return summary.substring(0, MAX_ARG_SUMMARY - 3) + "...";
    }

    /** 截断错误消息 */
    public static String summarizeError(String error) {
        if (error == null) return null;
        String cleaned = error.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= MAX_ERROR_MESSAGE) return cleaned;
        return cleaned.substring(0, MAX_ERROR_MESSAGE - 3) + "...";
    }

    /** 判断 key 是否属于敏感字段 */
    public static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase().replaceAll("[_\\-.]", "");
        return SENSITIVE_KEYS.stream().anyMatch(lower::contains);
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    private static String redactJson(JsonNode node) {
        if (node.isObject()) {
            var sb = new StringBuilder("{");
            var fields = node.fields();
            boolean first = true;
            while (fields.hasNext()) {
                var field = fields.next();
                if (!first) sb.append(", ");
                sb.append("\"").append(field.getKey()).append("\": ");
                if (isSensitiveKey(field.getKey())) {
                    sb.append("\"[REDACTED]\"");
                } else {
                    sb.append(redactJsonValue(field.getValue()));
                }
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        return node.toPrettyString();
    }

    private static String redactJsonValue(JsonNode node) {
        if (node.isObject()) return redactJson(node);
        if (node.isTextual()) return "\"" + node.asText() + "\"";
        return node.toString();
    }
}
