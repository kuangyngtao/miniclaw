package com.clawkit.engine.impl;

import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.action.ActionDescriptor;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 注册 engine-internal tools (task/session_context/skill/memory/remember)。
 * 这些工具不经过 ToolRegistry，由引擎直接处理。
 *
 * <p>P1-G4：internal tools 同样进入 Side Effect Gate——注册时提供权威 metadata
 * 与 ActionDescriptor 生成器；副作用型 internal tool 缺 descriptor 时 fail closed。
 */
public class InternalToolRouter {

    private final Map<String, BiFunction<ToolExecutionRequest, ToolExecutionContext, ToolExecutionResult>> handlers
        = new ConcurrentHashMap<>();
    private final Map<String, ToolMetadata> metadata = new ConcurrentHashMap<>();
    private final Map<String, Function<ToolExecutionRequest, ActionDescriptor>> descriptors
        = new ConcurrentHashMap<>();

    /** 注册一个 internal tool handler */
    public void register(String toolName,
                          BiFunction<ToolExecutionRequest, ToolExecutionContext, ToolExecutionResult> handler) {
        handlers.put(toolName, handler);
    }

    /** 注册 handler + 权威 metadata + ActionDescriptor 生成器（副作用工具必须提供后者） */
    public void register(String toolName,
                          BiFunction<ToolExecutionRequest, ToolExecutionContext, ToolExecutionResult> handler,
                          ToolMetadata toolMetadata,
                          Function<ToolExecutionRequest, ActionDescriptor> descriptorFn) {
        handlers.put(toolName, handler);
        if (toolMetadata != null) metadata.put(toolName, toolMetadata);
        if (descriptorFn != null) descriptors.put(toolName, descriptorFn);
    }

    /** 是否为 internal tool */
    public boolean isInternal(String toolName) {
        return handlers.containsKey(toolName);
    }

    /** internal tool 的权威 metadata（未注册时 empty，调用方回退保守值） */
    public Optional<ToolMetadata> metadataOf(String toolName) {
        return Optional.ofNullable(metadata.get(toolName));
    }

    /** 生成 ActionDescriptor；无生成器或生成失败返回 null（由 Gate fail closed） */
    public ActionDescriptor describe(ToolExecutionRequest req) {
        var fn = descriptors.get(req.toolName());
        if (fn == null) return null;
        try {
            return fn.apply(req);
        } catch (Exception e) {
            return null;
        }
    }

    /** 执行 internal tool */
    public ToolExecutionResult execute(ToolExecutionRequest req, ToolExecutionContext ctx) {
        var handler = handlers.get(req.toolName());
        if (handler == null) {
            return ToolExecutionResult.error(req.toolCallId(), req.toolName(),
                "NOT_FOUND", "internal tool not found: " + req.toolName(), 0,
                ToolMetadata.conservative(req.toolName()));
        }
        return handler.apply(req, ctx);
    }
}
