package com.clawkit.observability;

import com.clawkit.tools.ToolRiskLevel;

/**
 * 审批决策事件 payload。
 *
 * <p>decision 取值：APPROVE / APPROVE_SAME_TYPE / REJECT / MODIFY /
 * NOT_REQUIRED / AUTO_APPROVED / PLAN_BLOCKED / SAFETY_BLOCKED。
 *
 * <p>source 取值：USER / PERMISSION_MODE / SAME_TYPE_CACHE /
 * TOOL_METADATA / SAFETY_INTERCEPTOR。
 */
public record ApprovalDecidedPayload(
    String toolCallId,
    String toolName,
    ToolRiskLevel riskLevel,
    String decision,
    String source,
    String reason
) implements RunEventPayload {
}
