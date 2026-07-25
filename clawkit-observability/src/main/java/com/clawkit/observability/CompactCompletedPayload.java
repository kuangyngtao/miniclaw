package com.clawkit.observability;

import java.util.List;
import java.util.Map;

/**
 * compact 完成事件 payload（P1-A6 扩展）。
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
    String errorCode,
    // ── P1-A6 兼容字段 ──────────────────────────────────────────────
    String profile,
    List<String> retainedAnchorIds,
    List<String> lostRequiredAnchorIds,
    List<String> discardedRangeSummaries,
    String failureCode,
    String level,
    String decisionReason
) implements RunEventPayload {

    public CompactCompletedPayload {
        if (sectionsBefore == null) sectionsBefore = Map.of();
        if (sectionsAfter == null) sectionsAfter = Map.of();
        if (appliedRules == null) appliedRules = List.of();
        if (profile == null) profile = "GENERAL";
        if (retainedAnchorIds == null) retainedAnchorIds = List.of();
        if (lostRequiredAnchorIds == null) lostRequiredAnchorIds = List.of();
        if (discardedRangeSummaries == null) discardedRangeSummaries = List.of();
        if (level == null) level = failed ? "L4_FAILED" : "L0_NONE";
        if (decisionReason == null) decisionReason = failureCode != null
            ? failureCode : errorCode;
    }

    /** P1-A6 canonical compatibility constructor, before adaptive levels were added. */
    public CompactCompletedPayload(
        int beforeMessages, int afterMessages,
        int beforeTokens, int afterTokens,
        String beforeStatus, String afterStatus,
        Map<String, Integer> sectionsBefore, Map<String, Integer> sectionsAfter,
        int evictedGroups, List<String> appliedRules,
        long durationMs, boolean failed, String errorCode,
        String profile, List<String> retainedAnchorIds,
        List<String> lostRequiredAnchorIds, List<String> discardedRangeSummaries,
        String failureCode
    ) {
        this(beforeMessages, afterMessages, beforeTokens, afterTokens,
            beforeStatus, afterStatus, sectionsBefore, sectionsAfter,
            evictedGroups, appliedRules, durationMs, failed, errorCode,
            profile, retainedAnchorIds, lostRequiredAnchorIds, discardedRangeSummaries,
            failureCode, failed ? "L4_FAILED" : "L0_NONE", failureCode);
    }

    /** 旧构造器兼容：P1-A6 字段默认 GENERAL/空 */
    public CompactCompletedPayload(
        int beforeMessages, int afterMessages,
        int beforeTokens, int afterTokens,
        String beforeStatus, String afterStatus,
        Map<String, Integer> sectionsBefore, Map<String, Integer> sectionsAfter,
        int evictedGroups, List<String> appliedRules,
        long durationMs, boolean failed, String errorCode
    ) {
        this(beforeMessages, afterMessages, beforeTokens, afterTokens,
            beforeStatus, afterStatus, sectionsBefore, sectionsAfter,
            evictedGroups, appliedRules, durationMs, failed, errorCode,
            "GENERAL", List.of(), List.of(), List.of(), null,
            failed ? "L4_FAILED" : "L0_NONE", errorCode);
    }
}
