package com.clawkit.tools;

import java.util.Set;

/**
 * 工具的完整元数据，包括风险等级和副作用。
 * 放置在 tools 模块，不依赖 engine。
 */
public record ToolMetadata(
    String name,
    String description,
    Object inputSchema,
    boolean readOnly,
    ToolRiskLevel riskLevel,
    boolean destructive,
    boolean requiresApproval,
    Set<ToolSideEffect> sideEffects
) {
    /** 从 ToolDefinition 和安全属性快速构造 */
    public static ToolMetadata from(Tool tool) {
        return new ToolMetadata(
            tool.name(),
            tool.description(),
            tool.inputSchema(),
            tool.isReadOnly(),
            tool.isReadOnly() ? ToolRiskLevel.LOW : ToolRiskLevel.MEDIUM,
            !tool.isReadOnly(),
            !tool.isReadOnly(),
            Set.of()
        );
    }

    /** 保守默认值（未知工具按高风险处理） */
    public static ToolMetadata conservative(String name) {
        return new ToolMetadata(
            name,
            "",
            null,
            false,
            ToolRiskLevel.HIGH,
            true,
            true,
            Set.of()
        );
    }
}
