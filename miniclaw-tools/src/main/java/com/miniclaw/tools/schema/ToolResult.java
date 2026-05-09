package com.miniclaw.tools.schema;

/**
 * 工具本地执行完毕后返回的结果。
 * isError 标记供上层进行错误自愈判断。
 */
public record ToolResult(
    String toolCallId,
    String output,
    boolean isError
) {
    public static ToolResult success(String toolCallId, String output) {
        return new ToolResult(toolCallId, output, false);
    }

    public static ToolResult error(String toolCallId, String output) {
        return new ToolResult(toolCallId, output, true);
    }
}
