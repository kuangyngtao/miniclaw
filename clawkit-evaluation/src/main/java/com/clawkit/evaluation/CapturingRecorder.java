package com.clawkit.evaluation;

import com.clawkit.observability.RunEventPayload;
import com.clawkit.observability.RunRecorder;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 捕获根 runId + 委托给 FileRunRecorder 写入真实 O1 链路。
 *
 * <p>用法：
 * <pre>{@code
 * var fileRecorder = new FileRunRecorder(runsBaseDir);
 * var capturing = new CapturingRecorder(fileRecorder);
 * engine.addRecorder(capturing);
 * engine.run(prompt);
 * // 之后：
 * String rootRunId = capturing.rootRunId();
 * List<String> allRunIds = capturing.allRunIds();
 * }</pre>
 *
 * <p>第一个非 SubAgent 的 run 被记录为 rootRunId。
 * 所有 runId（包括 SubAgent）都被收集。
 */
public class CapturingRecorder implements RunRecorder {

    private final RunRecorder delegate;
    private final Set<String> runIds = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<String> orderedRunIds = new CopyOnWriteArrayList<>();
    private volatile String rootRunId;

    public CapturingRecorder(RunRecorder delegate) {
        this.delegate = delegate;
    }

    @Override
    public void record(RunEventPayload payload, String runId, String parentRunId,
                        Integer turnNumber, Instant occurredAt) {
        // 捕获 runId
        if (runIds.add(runId)) {
            orderedRunIds.add(runId);
            // 第一个没有 parentRunId 的 run 是根 run
            if (rootRunId == null && parentRunId == null) {
                rootRunId = runId;
            }
        }

        // 委托给真实 recorder
        delegate.record(payload, runId, parentRunId, turnNumber, occurredAt);
    }

    /** 根 run 的 ID（第一个没有 parentRunId 的 run） */
    public String rootRunId() {
        return rootRunId;
    }

    /** 所有 run ID（包括 SubAgent） */
    public Set<String> allRunIds() {
        return Set.copyOf(runIds);
    }

    /** 按创建顺序排列的 run ID */
    public java.util.List<String> orderedRunIds() {
        return java.util.List.copyOf(orderedRunIds);
    }
}
