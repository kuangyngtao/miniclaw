package com.miniclaw.tools.mcp;

import java.util.List;
import java.util.Map;

/** 单个 MCP server 的配置。 */
public record McpServerConfig(
    String name,
    String command,
    List<String> args,
    String url,
    Map<String, String> env
) {
    public enum Transport { STDIO, HTTP }

    public Transport transport() {
        return url != null && !url.isBlank() ? Transport.HTTP : Transport.STDIO;
    }

    public boolean disabled() {
        return false;
    }
}
