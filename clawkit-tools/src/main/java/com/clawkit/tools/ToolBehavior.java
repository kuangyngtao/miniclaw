package com.clawkit.tools;

import java.util.Set;

/**
 * 工具行为定义，参与风险决策。
 * 将只读、风险等级、破坏性、幂等性、开放世界等正交维度从 ToolMetadata 中拆出。
 */
public record ToolBehavior(
    boolean readOnly,
    ToolRiskLevel riskLevel,
    boolean destructive,
    boolean idempotent,
    boolean openWorld,
    boolean requiresApproval,
    Set<ToolSideEffect> sideEffects
) {
    /** 从旧 ToolMetadata 平铺字段构造（迁移适配） */
    public static ToolBehavior from(ToolMetadata old) {
        return new ToolBehavior(
            old.readOnly(),
            old.riskLevel(),
            old.destructive(),
            false,   // idempotent — 旧 metadata 未表达
            false,   // openWorld — 旧 metadata 未表达
            old.requiresApproval(),
            old.sideEffects()
        );
    }

    /** LOW 只读非破坏工具的安全默认值 */
    public static ToolBehavior safeDefault() {
        return new ToolBehavior(
            true, ToolRiskLevel.LOW, false, true, false, false, Set.of()
        );
    }

    /** 未知工具的保守默认值 */
    public static ToolBehavior conservativeDefault() {
        return new ToolBehavior(
            false, ToolRiskLevel.HIGH, true, false, true, true, Set.of()
        );
    }
}
