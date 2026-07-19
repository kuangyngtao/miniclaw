package com.clawkit.engine;

import com.clawkit.tools.control.ExecutionControl;
import java.util.Objects;

/**
 * 运行作用域：不可变 record，携带 run 标识和当前 phase。
 * 每次 nextTurn() / child() 返回新对象。
 *
 * <p>P1-G：携带 {@link ExecutionControl}，取消、deadline 和预算随作用域
 * 贯穿 Provider 与工具链。
 */
public record RunScope(
    String runId,
    String parentRunId,
    int turn,
    RunPhase phase,
    ExecutionMode executionMode,
    ExecutionControl control
) {
    public RunScope {
        Objects.requireNonNull(runId, "runId required");
        Objects.requireNonNull(phase, "phase required");
        if (turn < 0) throw new IllegalArgumentException("turn must be >= 0");
        if (control == null) control = ExecutionControl.none();
    }

    /** 兼容构造器（无 ExecutionControl） */
    public RunScope(String runId, String parentRunId, int turn,
                    RunPhase phase, ExecutionMode executionMode) {
        this(runId, parentRunId, turn, phase, executionMode, ExecutionControl.none());
    }

    public RunScope nextTurn(int nextTurn) {
        return new RunScope(runId, parentRunId, nextTurn, phase, executionMode, control);
    }

    public RunScope withPhase(RunPhase nextPhase) {
        return new RunScope(runId, parentRunId, turn, nextPhase, executionMode, control);
    }

    public RunScope child(String childRunId, RunPhase childPhase) {
        return new RunScope(childRunId, runId, 0, childPhase, executionMode, control);
    }
}
