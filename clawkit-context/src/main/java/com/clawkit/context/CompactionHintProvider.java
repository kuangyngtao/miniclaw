package com.clawkit.context;

/**
 * P1-A6：CompactionHint 提供者 — Engine 每轮取一次不可变 snapshot。
 * 默认实现永远返回 GENERAL；OPS workflow 注入自己的 provider。
 */
@FunctionalInterface
public interface CompactionHintProvider {
    CompactionHint snapshot(String runId, int turn);

    /** 始终返回 GENERAL 的默认实现 */
    static CompactionHintProvider general() {
        return (runId, turn) -> CompactionHint.GENERAL;
    }
}
