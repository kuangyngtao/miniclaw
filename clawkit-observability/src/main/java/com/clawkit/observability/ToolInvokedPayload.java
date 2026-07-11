package com.clawkit.observability;

import com.clawkit.tools.ToolRiskLevel;

/**
 * 工具调用启动事件 payload。
 * 风险信息来自 ToolMetadata，不按工具名推断。
 */
public record ToolInvokedPayload(
    String toolCallId,
    String toolName,
    String argSummary,
    boolean internal,
    boolean readOnly,
    ToolRiskLevel riskLevel,
    boolean destructive,
    boolean approvalRequired
) implements RunEventPayload {
}
