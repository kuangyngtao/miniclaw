package com.clawkit.observability;

/**
 * 事件 payload 的 sealed interface。
 * 每个子类型对应一种事件的具体数据，不包含 runId / sequence 等公共字段。
 */
public sealed interface RunEventPayload
    permits RunStartedPayload,
            RunCompletedPayload,
            TurnStartedPayload,
            TurnCompletedPayload,
            ContextPreparedPayload,
            ProviderCallStartedPayload,
            ProviderCallCompletedPayload,
            ToolInvokedPayload,
            ToolCompletedPayload,
            ApprovalDecidedPayload,
            CompactTriggeredPayload,
            CompactCompletedPayload,
            AttemptTransitionPayload,
            UnknownEventPayload {
}
