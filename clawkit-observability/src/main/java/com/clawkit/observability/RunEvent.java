package com.clawkit.observability;

import com.clawkit.observability.model.CompactMetrics;
import com.clawkit.observability.model.ProviderCallMetrics;
import com.clawkit.observability.model.ToolCallMetrics;
import com.clawkit.observability.model.TurnMetrics;

import java.time.Instant;

/**
 * 运行时可观测事件 sealed 接口。
 * AgentEngine 通过 fireRunEvent() 分发事件，FileRunRecorder 作为 Consumer 订阅。
 *
 * <p>每种事件对应一次生命周期钩子：run/turn/provider/tool/compact/approval。
 */
public sealed interface RunEvent {

    /** run 启动 */
    record RunStarted(
        String runId,
        String userPrompt,
        Instant startTime,
        String workDir,
        String model,
        String permissionMode,
        String thinkingMode,
        String executionMode
    ) implements RunEvent {}

    /** run 终止 */
    record RunCompleted(
        String runId,
        Instant endTime,
        RunStatus status,
        String errorCode,
        String errorMessage,
        int totalTurns,
        int totalToolCalls,
        int toolFailures,
        int compactCount
    ) implements RunEvent {}

    /** turn 启动 */
    record TurnStarted(
        String runId,
        int turnNumber,
        Instant startTime
    ) implements RunEvent {}

    /** turn 完成 */
    record TurnCompleted(
        String runId,
        TurnMetrics metrics
    ) implements RunEvent {}

    /** Provider 调用完成 */
    record ProviderCallCompleted(
        String runId,
        int turnNumber,
        ProviderCallMetrics metrics
    ) implements RunEvent {}

    /** 工具调用启动 */
    record ToolInvoked(
        String runId,
        int turnNumber,
        String toolCallId,
        String toolName,
        String argSummary,
        boolean isReadOnly
    ) implements RunEvent {}

    /** 工具调用完成 */
    record ToolCompleted(
        String runId,
        int turnNumber,
        ToolCallMetrics metrics
    ) implements RunEvent {}

    /** compact 触发 */
    record CompactTriggered(
        String runId,
        int turnNumber,
        Instant startTime
    ) implements RunEvent {}

    /** compact 完成 */
    record CompactCompleted(
        String runId,
        CompactMetrics metrics
    ) implements RunEvent {}

    /** 审批决策 */
    record ApprovalDecision(
        String runId,
        int turnNumber,
        String toolName,
        String decision,
        String reason
    ) implements RunEvent {}
}
