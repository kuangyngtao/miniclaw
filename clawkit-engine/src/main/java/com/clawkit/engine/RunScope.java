package com.clawkit.engine;

import java.util.Objects;

/**
 * 运行作用域：不可变 record，携带 run 标识和当前 phase。
 * 每次 nextTurn() / child() 返回新对象。
 */
public record RunScope(
    String runId,
    String parentRunId,
    int turn,
    RunPhase phase,
    ExecutionMode executionMode
) {
    public RunScope {
        Objects.requireNonNull(runId, "runId required");
        Objects.requireNonNull(phase, "phase required");
        if (turn < 0) throw new IllegalArgumentException("turn must be >= 0");
    }

    public RunScope nextTurn(int nextTurn) {
        return new RunScope(runId, parentRunId, nextTurn, phase, executionMode);
    }

    public RunScope withPhase(RunPhase nextPhase) {
        return new RunScope(runId, parentRunId, turn, nextPhase, executionMode);
    }

    public RunScope child(String childRunId, RunPhase childPhase) {
        return new RunScope(childRunId, runId, 0, childPhase, executionMode);
    }
}
