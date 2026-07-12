package com.clawkit.tools;

/**
 * 权限决策策略接口。
 * 所有模式的权限判断通过此接口统一，替代散落在 ToolCallExecutor/ApprovalRequest/AgentEngine 中的硬编码逻辑。
 *
 * <p>此接口放在 tools 模块，使 tools 可以独立表达和执行权限策略，
 * 不再依赖 engine 的 PermissionMode 枚举。
 */
public interface PermissionPolicy {

    /**
     * 评估工具执行权限。
     *
     * @param mode     权限模式（AUTO / ASK / PLAN）
     * @param metadata 工具的完整元数据（行为 + 执行策略 + 来源）
     * @param request  执行请求（含 scope）
     * @param grants   审批缓存（同一次 run 内已批准的同类操作）
     * @return 权限决策结果
     */
    PermissionDecision evaluate(
        PermissionMode mode,
        ToolMetadata metadata,
        ToolExecutionRequest request,
        ApprovalGrantCache grants
    );

    // ── nested types ──────────────────────────────────────────────

    /** 权限决策 */
    record PermissionDecision(
        PermissionOutcome outcome,
        String reasonCode,
        String reason
    ) {
        public static PermissionDecision allow() {
            return new PermissionDecision(PermissionOutcome.ALLOW, "POLICY_ALLOW", null);
        }

        public static PermissionDecision requireApproval(String reason) {
            return new PermissionDecision(PermissionOutcome.REQUIRE_APPROVAL, "POLICY_REQUIRE_APPROVAL", reason);
        }

        public static PermissionDecision deny(String reasonCode, String reason) {
            return new PermissionDecision(PermissionOutcome.DENY, reasonCode, reason);
        }
    }

    /** 权限结果 */
    enum PermissionOutcome {
        /** 允许直接执行 */
        ALLOW,
        /** 需要人工审批 */
        REQUIRE_APPROVAL,
        /** 拒绝执行 */
        DENY
    }
}
