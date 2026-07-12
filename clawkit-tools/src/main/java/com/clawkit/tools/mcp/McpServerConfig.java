package com.clawkit.tools.mcp;

import java.util.List;
import java.util.Map;

/** 单个 MCP server 的配置。 */
public record McpServerConfig(
    String name,
    String command,
    List<String> args,
    String url,
    Map<String, String> env,
    boolean disabled,
    // ── V2 字段 ──────────────────────────────────────────────────
    boolean trusted
) {
    public McpServerConfig(String name, String command, List<String> args,
                           String url, Map<String, String> env) {
        this(name, command, args, url, env, false, false);
    }

    public McpServerConfig(String name, String command, List<String> args,
                           String url, Map<String, String> env, boolean disabled) {
        this(name, command, args, url, env, disabled, false);
    }

    public enum Transport { STDIO, HTTP }

    public Transport transport() {
        return url != null && !url.isBlank() ? Transport.HTTP : Transport.STDIO;
    }
}
