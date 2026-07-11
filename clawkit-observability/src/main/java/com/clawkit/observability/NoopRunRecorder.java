package com.clawkit.observability;

import java.time.Instant;

/**
 * 空操作 RunRecorder，默认实现，不产生 IO 开销。
 * 当未配置观测存储时使用。
 */
public final class NoopRunRecorder implements RunRecorder {

    @Override
    public void record(RunEventPayload payload, String runId, String parentRunId,
                        Integer turnNumber, Instant occurredAt) {
        // no-op: 观测功能未启用
    }
}
