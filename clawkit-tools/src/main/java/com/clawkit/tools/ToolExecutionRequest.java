package com.clawkit.tools;

import com.clawkit.tools.schema.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 工具执行请求。
 *
 * <p>V2：增加 {@link ToolExecutionScope} 字段，携带 run/turn/workspace 上下文。
 */
public record ToolExecutionRequest(
    String toolCallId,
    String toolName,
    JsonNode arguments,
    Instant requestedAt,
    // ── V2 字段 ──────────────────────────────────────────────────
    ToolExecutionScope scope
) {
    // ── V2 规范构造器（带 scope） ────────────────────────────────

    public ToolExecutionRequest(
        String toolCallId,
        String toolName,
        JsonNode arguments,
        ToolExecutionScope scope
    ) {
        this(toolCallId, toolName, arguments, Instant.now(), scope);
    }

    // ── 旧构造器（不带 scope，保留兼容） ─────────────────────────

    /** 不带 scope 的构造器（保留兼容旧调用方） */
    public ToolExecutionRequest(
        String toolCallId,
        String toolName,
        JsonNode arguments,
        Instant requestedAt
    ) {
        this(toolCallId, toolName, arguments, requestedAt, null);
    }

    // ── 工厂方法 ──────────────────────────────────────────────────

    /** 从 ToolCall 创建（不带 scope） */
    public static ToolExecutionRequest from(ToolCall call) {
        return new ToolExecutionRequest(
            call.id(), call.name(), call.arguments(), Instant.now()
        );
    }

    /** 从 ToolCall 创建（带 scope） */
    public static ToolExecutionRequest from(ToolCall call, ToolExecutionScope scope) {
        return new ToolExecutionRequest(
            call.id(), call.name(), call.arguments(), Instant.now(), scope
        );
    }
}
