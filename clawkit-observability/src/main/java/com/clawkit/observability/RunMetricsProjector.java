package com.clawkit.observability;

import java.util.List;

/**
 * 从事件列表批量投影 RunMetrics。
 * 内部使用 RunAccumulator，保证与实时 recorder 使用同一套聚合逻辑。
 */
public final class RunMetricsProjector {

    private RunMetricsProjector() {}

    /**
     * 从完整事件列表投影指标。
     *
     * @param events 按 sequence 排序的事件列表
     * @return 投影出的 RunMetrics
     * @throws IllegalArgumentException 如果 events 为空
     */
    public static RunMetrics project(List<RunEventEnvelope> events) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("events must not be null or empty");
        }
        var acc = new RunAccumulator(events.getFirst().runId());
        for (var env : events) {
            acc.accept(env);
        }
        return acc.metrics();
    }

    /**
     * 从事件列表生成 RunSummary（用于回放验证）。
     * 应与实时 recorder 的 summary 完全一致。
     */
    public static RunSummary projectSummary(List<RunEventEnvelope> events) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("events must not be null or empty");
        }
        var acc = new RunAccumulator(events.getFirst().runId());
        for (var env : events) {
            acc.accept(env);
        }
        return acc.snapshot();
    }
}
