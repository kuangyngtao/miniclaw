package com.miniclaw.tools;

import com.miniclaw.tools.schema.ToolCall;
import com.miniclaw.tools.schema.ToolDefinition;
import com.miniclaw.tools.schema.ToolResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry 的默认实现 — 线程安全，支持运行时注册/注销。
 */
public class ToolRegistry implements Registry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();
    private final List<SafetyInterceptor> interceptors = new ArrayList<>();

    @Override
    public void register(Tool tool) {
        if (tools.containsKey(tool.name())) {
            log.warn("工具 '{}' 已被注册，将被覆盖。", tool.name());
        }
        tools.put(tool.name(), tool);
        log.info("成功挂载工具: {}", tool.name());
    }

    /** 批量注册 */
    public void registerAll(Collection<Tool> tools) {
        tools.forEach(this::register);
    }

    @Override
    public Optional<Tool> lookup(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 查询工具是否为只读工具 */
    public boolean isReadOnly(String toolName) {
        Tool tool = tools.get(toolName);
        return tool != null && tool.isReadOnly();
    }

    /** 注册安全拦截器 */
    public void addInterceptor(SafetyInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    @Override
    public List<ToolDefinition> getAvailableTools() {
        return tools.values().stream()
            .map(t -> new ToolDefinition(t.name(), t.description(), t.inputSchema()))
            .toList();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        for (SafetyInterceptor i : interceptors) {
            String blockReason = i.check(call);
            if (blockReason != null) {
                log.warn("[Registry] 工具调用被拦截: {}", blockReason);
                return ToolResult.error(call.id(), blockReason);
            }
        }
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.error(call.id(), "tool not found: " + call.name());
        }
        try {
            Result<String> result = tool.execute(
                call.arguments() != null ? call.arguments().toString() : "{}");
            return switch (result) {
                case Result.Ok<String> ok -> ToolResult.success(call.id(), ok.data());
                case Result.Err<String> err ->
                    ToolResult.error(call.id(),
                        "[" + err.error().errorCode() + "] " + err.error().message());
            };
        } catch (Exception e) {
            return ToolResult.error(call.id(), call.name() + " 执行异常: " + e.getMessage());
        }
    }

    /** 工具数量 */
    public int count() {
        return tools.size();
    }
}
