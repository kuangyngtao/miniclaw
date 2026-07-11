package com.clawkit.observability;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 未知事件 payload。
 * 读取未来 schema 或无法识别的事件类型时使用，
 * 保留原始 JSON 以便展示和诊断。
 */
public record UnknownEventPayload(
    String originalEventType,
    JsonNode raw
) implements RunEventPayload {
}
