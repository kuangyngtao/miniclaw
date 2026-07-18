package com.clawkit.engine.impl;

import com.clawkit.engine.ApprovalHandler;
import com.clawkit.observability.RunRecorder;
import com.clawkit.tools.ApprovalGrantCache;
import com.clawkit.tools.DefaultApprovalGrantCache;
import com.clawkit.tools.PermissionMode;

/**
 * Plan 执行上下文——每次 execute() 传入，不缓存在 PlanExecutor 中。
 */
public record PlanExecutionContext(
    PermissionMode permissionMode,
    ApprovalHandler approvalHandler,
    RunRecorder recorder,
    String parentRunId
) {
    /** 为 worker 创建 ToolExecutionContext */
    ToolExecutionContext workerContext() {
        return new ToolExecutionContext(
            parentRunId + "-plan", 0, permissionMode,
            new DefaultPermissionPolicy(), approvalHandler, recorder,
            new InternalToolRouter(), new DefaultApprovalGrantCache());
    }

    /** 为 reviewer 创建 ToolExecutionContext（PLAN 模式只读） */
    ToolExecutionContext reviewerContext() {
        return new ToolExecutionContext(
            parentRunId + "-reviewer", 0, PermissionMode.PLAN,
            new DefaultPermissionPolicy(), approvalHandler, recorder,
            new InternalToolRouter(), new DefaultApprovalGrantCache());
    }
}
