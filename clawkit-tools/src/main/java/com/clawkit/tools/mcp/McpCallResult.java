package com.clawkit.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * MCP 工具调用结果（V2 结构化返回）。
 * 替代旧 String-only 返回值，保留 isError 和非文本 content items。
 */
public record McpCallResult(
    String text,
    boolean isError,
    List<JsonNode> contentItems
) {
    public static McpCallResult success(String text, List<JsonNode> contentItems) {
        return new McpCallResult(text, false, contentItems != null ? contentItems : List.of());
    }

    public static McpCallResult error(String text, List<JsonNode> contentItems) {
        return new McpCallResult(text, true, contentItems != null ? contentItems : List.of());
    }
}
