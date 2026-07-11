package com.clawkit.observability;

import java.time.Instant;

/**
 * 运行观测记录器接口。
 * 接收 RunEventPayload 及上下文元数据，由 FileRunRecorder 写入本地存储。
 *
 * <p>AgentEngine 通过此接口发送事件，
 * FileRunRecorder 负责分配 sequence、编码 envelope、写入 events.jsonl 并聚合 summary。
 */
public interface RunRecorder {

    /**
     * 记录一个运行时事件。
     *
     * @param payload      事件数据
     * @param runId        所属 run
     * @param parentRunId  父 run（SubAgent 场景），无则为 null
     * @param turnNumber   当前 turn，无则为 null
     * @param occurredAt   事件发生时间
     */
    void record(RunEventPayload payload, String runId, String parentRunId,
                Integer turnNumber, Instant occurredAt);
}
