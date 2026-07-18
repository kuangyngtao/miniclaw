package com.clawkit.observability;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 组合 RunRecorder：将事件广播给多个 delegate recorder。
 * 单个 delegate 失败不影响其他 delegate，不会中断 Agent run。
 */
public final class CompositeRunRecorder implements RunRecorder {

    private static final Logger log = LoggerFactory.getLogger(CompositeRunRecorder.class);

    private final CopyOnWriteArrayList<RunRecorder> delegates = new CopyOnWriteArrayList<>();

    public CompositeRunRecorder() {}

    public CompositeRunRecorder(RunRecorder... initial) {
        for (var r : initial) {
            if (r != null && r != this) delegates.add(r);
        }
    }

    public CompositeRunRecorder(List<RunRecorder> initial) {
        for (var r : initial) {
            if (r != null && r != this) delegates.add(r);
        }
    }

    public void add(RunRecorder recorder) {
        if (recorder == null || recorder == this) return;
        delegates.addIfAbsent(recorder);
    }

    public void remove(RunRecorder recorder) {
        if (recorder != null) delegates.remove(recorder);
    }

    public List<RunRecorder> delegates() {
        return List.copyOf(delegates);
    }

    @Override
    public void record(RunEventPayload payload, String runId, String parentRunId,
                       Integer turnNumber, Instant timestamp) {
        for (var r : delegates) {
            try {
                r.record(payload, runId, parentRunId, turnNumber, timestamp);
            } catch (Exception e) {
                log.warn("[CompositeRecorder] delegate {} failed: {}",
                    r.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
