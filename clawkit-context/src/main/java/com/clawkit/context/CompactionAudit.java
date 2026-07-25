package com.clawkit.context;

import java.util.List;

/**
 * P1-A6：compact 审计记录 — 记录保留/丢失的 anchor、discard 范围和失败码。
 */
public record CompactionAudit(
    String profile,
    List<String> retainedAnchorIds,
    List<String> lostRequiredAnchorIds,
    List<DiscardedTurnRange> discardedRanges,
    int evictedGroups,
    long durationMs,
    String failureCode,
    CompactionLevel level,
    String decisionReason
) {
    public static final CompactionAudit EMPTY = new CompactionAudit(
        "GENERAL", List.of(), List.of(), List.of(), 0, 0, null,
        CompactionLevel.L0_NONE, "within-budget");

    public CompactionAudit(
        String profile,
        List<String> retainedAnchorIds,
        List<String> lostRequiredAnchorIds,
        List<DiscardedTurnRange> discardedRanges,
        int evictedGroups,
        long durationMs,
        String failureCode
    ) {
        this(profile, retainedAnchorIds, lostRequiredAnchorIds, discardedRanges,
            evictedGroups, durationMs, failureCode,
            failureCode == null ? CompactionLevel.L0_NONE : CompactionLevel.L4_FAILED,
            failureCode == null ? "within-budget" : failureCode);
    }

    public CompactionAudit {
        if (profile == null) profile = "GENERAL";
        if (retainedAnchorIds == null) retainedAnchorIds = List.of();
        if (lostRequiredAnchorIds == null) lostRequiredAnchorIds = List.of();
        if (discardedRanges == null) discardedRanges = List.of();
        if (level == null) level = failureCode == null
            ? CompactionLevel.L0_NONE : CompactionLevel.L4_FAILED;
        if (decisionReason == null) decisionReason = failureCode;
    }

    public boolean failed() { return failureCode != null && !failureCode.isBlank(); }
}
