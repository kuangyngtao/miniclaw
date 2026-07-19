package com.clawkit.engine.impl;

import com.clawkit.engine.ApprovalHandler;
import com.clawkit.observability.RunRecorder;
import com.clawkit.tools.ApprovalGrantCache;
import com.clawkit.tools.DefaultApprovalGrantCache;
import com.clawkit.tools.PermissionMode;
import com.clawkit.tools.control.ExecutionControl;

/**
 * Plan 执行上下文——每次 execute() 传入，不缓存在 PlanExecutor 中。
 *
 * <p>P1-G1：携带 {@link ExecutionControl}，worker/reviewer 的模型与工具调用
 * 共享同一取消信号、deadline 和预算。
 */
public record PlanExecutionContext(
    PermissionMode permissionMode,
    ApprovalHandler approvalHandler,
    RunRecorder recorder,
    String parentRunId,
    ExecutionControl control
) {
    public PlanExecutionContext {
        if (control == null) control = ExecutionControl.none();
    }

    /** 兼容构造器（无 ExecutionControl） */
    public PlanExecutionContext(PermissionMode permissionMode, ApprovalHandler approvalHandler,
                                RunRecorder recorder, String parentRunId) {
        this(permissionMode, approvalHandler, recorder, parentRunId, ExecutionControl.none());
    }

    /** 为 worker 创建 ToolExecutionContext */
    ToolExecutionContext workerContext() {
        return new ToolExecutionContext(
            parentRunId + "-plan", 0, permissionMode,
            new DefaultPermissionPolicy(), approvalHandler, recorder,
            new InternalToolRouter(), new DefaultApprovalGrantCache(), control);
    }

    /** 为 reviewer 创建 ToolExecutionContext（PLAN 模式只读） */
    ToolExecutionContext reviewerContext() {
        return new ToolExecutionContext(
            parentRunId + "-reviewer", 0, PermissionMode.PLAN,
            new DefaultPermissionPolicy(), approvalHandler, recorder,
            new InternalToolRouter(), new DefaultApprovalGrantCache(), control);
    }
}
