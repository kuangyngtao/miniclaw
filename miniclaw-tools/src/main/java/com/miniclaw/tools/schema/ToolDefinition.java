package com.miniclaw.tools.schema;

/**
 * 工具元信息 — 发送给大模型，让模型理解工具有什么用。
 */
public record ToolDefinition(
    String name,
    String description,
    Object inputSchema
) {}
