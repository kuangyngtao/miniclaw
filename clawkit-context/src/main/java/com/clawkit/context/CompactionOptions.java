package com.clawkit.context;

import com.clawkit.context.impl.TurnGroup;

import java.util.List;

/**
 * P1-A6：compact 选项，传递 profile 和驱逐信息。
 */
public record CompactionOptions(
    CompactionProfile profile,
    List<TurnGroup> evictedTurnGroups,
    CompactionLevel maxLevel
) {
    public static final CompactionOptions GENERAL = new CompactionOptions(
        CompactionProfile.GENERAL, List.of(), CompactionLevel.L3_GENERATIVE);

    public CompactionOptions(CompactionProfile profile, List<TurnGroup> evictedTurnGroups) {
        this(profile, evictedTurnGroups, CompactionLevel.L3_GENERATIVE);
    }

    public CompactionOptions {
        if (profile == null) profile = CompactionProfile.GENERAL;
        if (evictedTurnGroups == null) evictedTurnGroups = List.of();
        if (maxLevel == null) maxLevel = CompactionLevel.L3_GENERATIVE;
    }
}
