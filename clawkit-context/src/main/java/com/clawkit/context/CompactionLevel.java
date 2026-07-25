package com.clawkit.context;

/** The least aggressive compaction layer selected for a context build. */
public enum CompactionLevel {
    L0_NONE,
    L1_DETERMINISTIC,
    L2_EXTRACTIVE,
    L3_GENERATIVE,
    L4_FAILED;

    public boolean atLeast(CompactionLevel other) {
        return ordinal() >= other.ordinal();
    }
}
