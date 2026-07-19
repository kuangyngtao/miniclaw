package com.clawkit.context;

import java.util.List;

/**
 * P1-A6：compaction hint — 传递给 compact 管线的任务感知提示。
 */
public record CompactionHint(
    CompactionProfile profile,
    List<CompactionAnchor> anchors
) {
    public static final CompactionHint GENERAL = new CompactionHint(
        CompactionProfile.GENERAL, List.of());

    public CompactionHint {
        if (profile == null) profile = CompactionProfile.GENERAL;
        if (anchors == null) anchors = List.of();
        anchors = List.copyOf(anchors);
    }
}
