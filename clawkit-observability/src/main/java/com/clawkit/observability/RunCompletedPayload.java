package com.clawkit.observability;

import com.clawkit.observability.RunStatus;

/**
 * run 完成事件 payload。
 * 不包含 totalTurns/toolCalls 等聚合字段——这些由 RunAccumulator 从事件投影。
 */
public record RunCompletedPayload(
    RunStatus status,
    String errorCode,
    String errorMessage
) implements RunEventPayload {
}
