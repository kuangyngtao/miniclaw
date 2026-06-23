package com.miniclaw.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/** MCP server 返回的工具定义（内部使用，清洗前）。 */
public record McpToolDef(String name, String description, JsonNode inputSchema) {}
