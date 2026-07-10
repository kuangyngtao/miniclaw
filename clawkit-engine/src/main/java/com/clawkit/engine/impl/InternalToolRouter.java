package com.clawkit.engine.impl;

import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolRiskLevel;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * 注册 engine-internal tools (task/session_context/skill/memory/remember)。
 * 这些工具不经过 ToolRegistry，由引擎直接处理。
 */
public class InternalToolRouter {

    private final Map<String, BiFunction<ToolExecutionRequest, ToolExecutionContext, ToolExecutionResult>> handlers
        = new ConcurrentHashMap<>();

    /** 注册一个 internal tool handler */
    public void register(String toolName,
                          BiFunction<ToolExecutionRequest, ToolExecutionContext, ToolExecutionResult> handler) {
        handlers.put(toolName, handler);
    }

    /** 是否为 internal tool */
    public boolean isInternal(String toolName) {
        return handlers.containsKey(toolName);
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
