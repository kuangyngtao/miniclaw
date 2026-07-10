package com.clawkit.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 工具执行请求，仅包含执行需要的数据。
 * runId、turnNumber、permissionMode 等 engine 侧信息由 ToolExecutionContext 携带。
 */
public record ToolExecutionRequest(
    String toolCallId,
    String toolName,
    JsonNode arguments,
    Instant requestedAt
) {
    public static ToolExecutionRequest from(com.clawkit.tools.schema.ToolCall call) {
        return new ToolExecutionRequest(
            call.id(),
            call.name(),
            call.arguments(),
            Instant.now()
        );
    }
}
