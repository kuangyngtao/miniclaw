package com.clawkit.observability;

/**
 * P1-G：副作用 Attempt 状态迁移事件（attempt / outcome-unknown / reconcile /
 * verification 均通过 state 字段表达）。
 * 观测只做事实投影：RunEvent 写入失败不改变控制面状态。
 */
public record AttemptTransitionPayload(
    String attemptId,
    String actionCode,
    String targetKey,
    String state,
    String purpose,
    String relatedAttemptId,
    String reason
) implements RunEventPayload {
}
