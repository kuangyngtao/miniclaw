package com.clawkit.tools;

import com.clawkit.tools.schema.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;

/**
 * 统一工具契约。
 * 新工具优先实现 {@link #execute(ToolExecutionRequest)} 和 {@link #metadata()}。
 * {@link #execute(String)} 仅保留为迁移适配层。
 */
public interface Tool {
    ObjectMapper MAPPER = new ObjectMapper();

    /** 工具名称（kebab-case） */
    String name();

    /** 工具描述（给 LLM 阅读） */
    String description();

    /** JSON Schema 字符串 */
    String inputSchema();

    /**
     * 执行工具（旧接口）。
     * @deprecated 使用 {@link #execute(ToolExecutionRequest)} 替代。
     *             仅允许通过 LegacyToolAdapter 调用。
     */
    @Deprecated
    Result<String> execute(String arguments);

    /** 是否只读。默认 false（写工具）。读工具可被并行化 */
    default boolean isReadOnly() {
        return false;
    }

    /** 工具元数据（V2：包含行为、执行策略和来源） */
    default ToolMetadata metadata() {
        return ToolMetadata.from(this);
    }

    /** V2：工具行为定义 */
    default ToolBehavior behavior() {
        return metadata().behavior();
    }

    /** V2：工具执行策略 */
    default ToolExecutionPolicy executionPolicy() {
        return metadata().executionPolicy();
    }

    /**
     * P1-G：根据本次请求参数生成动作描述符。
     *
     * <p>副作用工具（readOnly=false 或声明了 side effect）必须覆盖此方法；
     * 返回 null 时统一执行器按 fail-closed 拒绝执行该副作用调用。
     * 只读工具无需实现。
     */
    default com.clawkit.tools.action.ActionDescriptor describeAction(ToolExecutionRequest req) {
        return null;
    }

    /**
     * 执行工具（V2 结构化接口）。
     * 默认实现适配旧 {@link #execute(String)}。
     */
    default ToolExecutionResult execute(ToolExecutionRequest req) {
        Instant start = Instant.now();
        try {
            String argsJson = req.arguments() != null ? req.arguments().toString() : "{}";
            Result<String> result = execute(argsJson);
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            return switch (result) {
                case Result.Ok<String> ok -> ToolExecutionResult.success(
                    req.toolCallId(), req.toolName(), ok.data(), durationMs, metadata());
                case Result.Err<String> err -> ToolExecutionResult.error(
                    req.toolCallId(), req.toolName(),
                    err.error().errorCode(), err.error().message(), durationMs, metadata());
            };
        } catch (Exception e) {
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            return ToolExecutionResult.internalError(
                req.toolCallId(), req.toolName(), e.getMessage(), durationMs, metadata());
        }
    }

    /** 转换为 LLM 可见的 ToolDefinition */
    default ToolDefinition toDefinition() {
        JsonNode schema = null;
        try {
            schema = MAPPER.readTree(inputSchema());
        } catch (Exception ignored) {
            // fallback: 传 null schema
        }
        return new ToolDefinition(name(), description(), schema);
    }
}
