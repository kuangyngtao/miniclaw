package com.clawkit.observability;

import java.util.List;
import java.util.Map;

/**
 * compact 完成事件 payload。
 * 记录压缩前后指标、耗时和失败状态。
 */
public record CompactCompletedPayload(
    int beforeMessages,
    int afterMessages,
    int beforeTokens,
    int afterTokens,
    String beforeStatus,
    String afterStatus,
    Map<String, Integer> sectionsBefore,
    Map<String, Integer> sectionsAfter,
    int evictedGroups,
    List<String> appliedRules,
    long durationMs,
    boolean failed,
    String errorCode
) implements RunEventPayload {
}
