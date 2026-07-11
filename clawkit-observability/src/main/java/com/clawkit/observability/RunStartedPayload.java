package com.clawkit.observability;

/**
 * run 启动事件 payload。
 * 禁止写入原始 userPrompt；taskSummary 已脱敏并截断到 160 字符。
 */
public record RunStartedPayload(
    String taskSummary,
    String workDir,
    String model,
    String permissionMode,
    String thinkingMode,
    String executionMode
) implements RunEventPayload {
}
