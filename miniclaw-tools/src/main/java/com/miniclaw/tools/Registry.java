package com.miniclaw.tools;

import com.miniclaw.tools.schema.ToolCall;
import com.miniclaw.tools.schema.ToolDefinition;
import com.miniclaw.tools.schema.ToolResult;
import java.util.List;

/**
 * 工具注册与分发执行接口 — 管理挂载的工具并执行模型请求的工具调用。
 */
public interface Registry {

    /** 返回所有已注册工具的元信息，供大模型理解可用能力 */
    List<ToolDefinition> getAvailableTools();

    /** 执行模型请求的工具调用，返回执行结果 */
    ToolResult execute(ToolCall call);

    /** 注册工具 */
    void register(Tool tool);

    /** 按名称查找 */
    java.util.Optional<Tool> lookup(String name);

    /** 查询指定名称的工具是否为只读工具 */
    boolean isReadOnly(String toolName);
}
