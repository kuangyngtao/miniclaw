package com.clawkit.engine;

/** Run 生命周期阶段。 */
public enum RunPhase {
    REACT,
    TWO_STAGE_PLAN,
    TWO_STAGE_EXECUTE,
    PLAN_WORKER,
    PLAN_REVIEWER,
    SUB_AGENT,
    COMPACT,
    MEMORY_EXTRACT
}
