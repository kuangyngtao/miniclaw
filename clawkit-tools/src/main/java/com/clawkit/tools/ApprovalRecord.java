package com.clawkit.tools;

/**
 * 审批记录，嵌入 ToolExecutionResult。
 */
public record ApprovalRecord(
    ApprovalDecision decision,
    ApprovalSource source,
    String approvalId,
    String reason
) {
    public static ApprovalRecord approved(String approvalId) {
        return new ApprovalRecord(ApprovalDecision.APPROVE, ApprovalSource.USER, approvalId, null);
    }

    public static ApprovalRecord approvedSameType(String approvalId, String toolName) {
        return new ApprovalRecord(
            ApprovalDecision.APPROVE_SAME_TYPE, ApprovalSource.USER, approvalId, toolName
        );
    }

    public static ApprovalRecord rejected(String approvalId, String reason) {
        return new ApprovalRecord(ApprovalDecision.REJECT, ApprovalSource.USER, approvalId, reason);
    }

    public static ApprovalRecord blockedByPolicy(String reasonCode) {
        return new ApprovalRecord(
            ApprovalDecision.NOT_REQUIRED, ApprovalSource.POLICY, null, reasonCode
        );
    }

    public static ApprovalRecord blockedBySafety(String reason) {
        return new ApprovalRecord(
            ApprovalDecision.NOT_REQUIRED, ApprovalSource.SAFETY, null, reason
        );
    }

    // ── nested types ──────────────────────────────────────────────

    public enum ApprovalDecision {
        APPROVE,
        APPROVE_SAME_TYPE,
        REJECT,
        MODIFY,
        /** 无需审批（策略允许或安全阻断） */
        NOT_REQUIRED
    }

    public enum ApprovalSource {
        USER,
        POLICY,
        SAFETY
    }
}
