package com.clawkit.context;

import com.clawkit.context.impl.TurnGroup;

import java.util.List;

/**
 * P1-A6：compact 选项，传递 profile 和驱逐信息。
 */
public record CompactionOptions(
    CompactionProfile profile,
    List<TurnGroup> evictedTurnGroups
) {
    public static final CompactionOptions GENERAL = new CompactionOptions(
        CompactionProfile.GENERAL, List.of());

    public CompactionOptions {
        if (profile == null) profile = CompactionProfile.GENERAL;
        if (evictedTurnGroups == null) evictedTurnGroups = List.of();
    }
}
