package com.clawkit.tools;

/**
 * 工具元数据来源和可信度。
 * 决定 MCP annotations 能否用于放宽风险限制。
 */
public record ToolMetadataProvenance(
    ToolMetadataSource source,
    boolean trusted,
    String sourceId
) {
    /** 内置工具的可信来源 */
    public static ToolMetadataProvenance builtin(String toolName) {
        return new ToolMetadataProvenance(ToolMetadataSource.BUILTIN, true, toolName);
    }

    /** 保守默认来源（未知工具） */
    public static ToolMetadataProvenance conservativeDefault() {
        return new ToolMetadataProvenance(
            ToolMetadataSource.CONSERVATIVE_DEFAULT, false, "unknown"
        );
    }

    /** MCP 工具来源（trusted 取决于用户配置） */
    public static ToolMetadataProvenance mcp(String serverName, String toolName, boolean trusted) {
        return new ToolMetadataProvenance(
            ToolMetadataSource.MCP_ANNOTATION, trusted, serverName + "/" + toolName
        );
    }

    // ── nested type ───────────────────────────────────────────────

    public enum ToolMetadataSource {
        /** 内置工具 */
        BUILTIN,
        /** MCP 服务端 annotations */
        MCP_ANNOTATION,
        /** 用户本地覆盖 */
        LOCAL_OVERRIDE,
        /** 保守默认值 */
        CONSERVATIVE_DEFAULT
    }
}
