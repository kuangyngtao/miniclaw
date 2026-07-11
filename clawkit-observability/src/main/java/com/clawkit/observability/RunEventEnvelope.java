package com.clawkit.observability;

import java.time.Instant;

/**
 * 运行时事件的通用序列化包装。
 * 公共字段（runId、sequence 等）由 envelope 携带，
 * 事件特定数据由 payload 携带。
 *
 * <p>落盘 JSON 格式：
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "eventId": "evt-001",
 *   "eventType": "tool_completed",
 *   "sequence": 8,
 *   "occurredAt": "2026-07-11T10:00:02.120Z",
 *   "recordedAt": "2026-07-11T10:00:02.122Z",
 *   "runId": "run-001",
 *   "parentRunId": null,
 *   "turnNumber": 2,
 *   "payload": { ... }
 * }
 * }</pre>
 */
public record RunEventEnvelope(
    int schemaVersion,
    String eventId,
    String eventType,
    long sequence,
    Instant occurredAt,
    Instant recordedAt,
    String runId,
    String parentRunId,
    Integer turnNumber,
    RunEventPayload payload
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
