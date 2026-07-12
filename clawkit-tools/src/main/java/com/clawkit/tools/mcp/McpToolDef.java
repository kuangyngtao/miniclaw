package com.clawkit.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP server 返回的工具定义。
 *
 * <p>V2：增加 annotations 和 outputSchema 字段，支持 MCP 2025-11-25 工具模型。
 */
public record McpToolDef(
    String name,
    String description,
    JsonNode inputSchema,
    // ── V2 字段 ──────────────────────────────────────────────────
    JsonNode annotations,
    JsonNode outputSchema
) {
    /** 旧构造器（无 annotations/outputSchema） */
    public McpToolDef(String name, String description, JsonNode inputSchema) {
        this(name, description, inputSchema, null, null);
    }
}
