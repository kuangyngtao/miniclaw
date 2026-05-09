package com.miniclaw.tools;

import com.miniclaw.tools.schema.ToolCall;
import com.miniclaw.tools.schema.ToolDefinition;
import com.miniclaw.tools.schema.ToolResult;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry 的默认实现 — 线程安全，支持运行时注册/注销。
 */
public class ToolRegistry implements Registry {

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();

    @Override
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /** 批量注册 */
    public void registerAll(Collection<Tool> tools) {
        tools.forEach(this::register);
    }

    @Override
    public Optional<Tool> lookup(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public List<ToolDefinition> getAvailableTools() {
        return tools.values().stream()
            .map(t -> new ToolDefinition(t.name(), t.description(), t.inputSchema()))
            .toList();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.error(call.id(), "tool not found: " + call.name());
        }
        try {
            Result<String> result = tool.execute(
                call.arguments() != null ? call.arguments().toString() : "{}");
            return switch (result) {
                case Result.Ok<String> ok -> ToolResult.success(call.id(), ok.data());
                case Result.Err<String> err -> ToolResult.error(call.id(), err.error().message());
            };
        } catch (Exception e) {
            return ToolResult.error(call.id(), e.getMessage());
        }
    }

    /** 工具数量 */
    public int count() {
        return tools.size();
    }
}
