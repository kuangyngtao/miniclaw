package com.miniclaw.tools.schema;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 模型请求调用某个工具。
 * arguments 使用 JsonNode 延迟解析，将解析责任交给具体的工具实现。
 */
public record ToolCall(
    String id,
    String name,
    JsonNode arguments
) {}
