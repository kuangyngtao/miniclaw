package com.clawkit.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;

/**
 * 工具的完整元数据，包括风险等级和副作用。
 * 放置在 tools 模块，不依赖 engine。
 *
 * <p>V2：行为、执行策略和来源拆分为独立组合对象。
 * 旧平铺字段保留为兼容层。
 * <p>compact constructor 保证平铺字段与 ToolBehavior 一致。
 */
public record ToolMetadata(
    String name,
    String description,
    JsonNode inputSchema,
    JsonNode outputSchema,
    boolean readOnly,
    ToolRiskLevel riskLevel,
    boolean destructive,
    boolean requiresApproval,
    Set<ToolSideEffect> sideEffects,
    // ── V2 组合对象 ──────────────────────────────────────────────
    ToolBehavior behavior,
    ToolExecutionPolicy executionPolicy,
    ToolMetadataProvenance provenance
) {
    /** compact constructor：验证平铺字段与 ToolBehavior 一致 */
    public ToolMetadata {
        if (behavior != null) {
            if (readOnly != behavior.readOnly())
                throw new IllegalArgumentException(
                    "readOnly=" + readOnly + " != behavior.readOnly=" + behavior.readOnly());
            if (riskLevel != behavior.riskLevel())
                throw new IllegalArgumentException(
                    "riskLevel=" + riskLevel + " != behavior.riskLevel=" + behavior.riskLevel());
            if (destructive != behavior.destructive())
                throw new IllegalArgumentException(
                    "destructive=" + destructive + " != behavior.destructive=" + behavior.destructive());
            if (requiresApproval != behavior.requiresApproval())
                throw new IllegalArgumentException(
                    "requiresApproval=" + requiresApproval
                    + " != behavior.requiresApproval=" + behavior.requiresApproval());
            if (!sideEffects.equals(behavior.sideEffects()))
                throw new IllegalArgumentException(
                    "sideEffects=" + sideEffects + " != behavior.sideEffects=" + behavior.sideEffects());
        }
    }

    // ── V2 规范构造器（组合对象为主） ────────────────────────────

    public ToolMetadata(
        String name,
        String description,
        JsonNode inputSchema,
        JsonNode outputSchema,
        ToolBehavior behavior,
        ToolExecutionPolicy executionPolicy,
        ToolMetadataProvenance provenance
    ) {
        this(
            name, description, inputSchema, outputSchema,
            behavior.readOnly(), behavior.riskLevel(),
            behavior.destructive(), behavior.requiresApproval(),
            behavior.sideEffects(),
            behavior, executionPolicy, provenance
        );
    }

    // ── 工厂方法 ──────────────────────────────────────────────────

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ToolMetadata from(Tool tool) {
        String schemaStr = tool.inputSchema();
        JsonNode schema = null;
        if (schemaStr != null && !schemaStr.isEmpty()) {
            try { schema = MAPPER.readTree(schemaStr); } catch (Exception ignored) {}
        }
        boolean ro = tool.isReadOnly();
        ToolRiskLevel level = ro ? ToolRiskLevel.LOW : ToolRiskLevel.MEDIUM;
        boolean dest = !ro;
        boolean reqApproval = !ro;
        Set<ToolSideEffect> fx = Set.of();

        return new ToolMetadata(
            tool.name(), tool.description(), schema, null,
            ro, level, dest, reqApproval, fx,
            new ToolBehavior(ro, level, dest, false, false, reqApproval, fx),
            ToolExecutionPolicy.defaults(),
            ToolMetadataProvenance.builtin(tool.name())
        );
    }

    @Deprecated
    public static ToolMetadata of(
        String name, String description, JsonNode inputSchema,
        boolean readOnly, ToolRiskLevel riskLevel,
        boolean destructive, boolean requiresApproval,
        Set<ToolSideEffect> sideEffects
    ) {
        return new ToolMetadata(
            name, description, inputSchema, null,
            readOnly, riskLevel, destructive, requiresApproval, sideEffects,
            new ToolBehavior(readOnly, riskLevel, destructive, false, false, requiresApproval, sideEffects),
            ToolExecutionPolicy.defaults(),
            ToolMetadataProvenance.conservativeDefault()
        );
    }

    public static ToolMetadata conservative(String name) {
        return new ToolMetadata(
            name, "", null, null,
            false, ToolRiskLevel.HIGH, true, true, Set.of(),
            ToolBehavior.conservativeDefault(),
            ToolExecutionPolicy.defaults(),
            ToolMetadataProvenance.conservativeDefault()
        );
    }

    // ── 便利方法 ──────────────────────────────────────────────────

    public boolean isReadOnly() { return readOnly; }
    public boolean isDestructive() { return destructive; }
    public boolean isApprovalRequired() { return requiresApproval; }
}
