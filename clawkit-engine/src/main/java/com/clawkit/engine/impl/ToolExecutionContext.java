package com.clawkit.engine.impl;

import com.clawkit.engine.ApprovalHandler;
import com.clawkit.observability.RunRecorder;
import com.clawkit.tools.ApprovalGrantCache;
import com.clawkit.tools.PermissionMode;
import com.clawkit.tools.PermissionPolicy;

/**
 * 工具执行上下文，将 engine 侧信息传递给 ToolCallExecutor。
 *
 * <p>V2：使用 tools 模块的 PermissionMode/PermissionPolicy/ApprovalGrantCache。
 */
public record ToolExecutionContext(
    String runId,
    int turnNumber,
    PermissionMode permissionMode,
    PermissionPolicy permissionPolicy,
    ApprovalHandler approvalHandler,
    RunRecorder recorder,
    InternalToolRouter internalTools,
    ApprovalGrantCache approvalCache
) {
    /** 兼容构造器（无 PermissionPolicy，使用内联逻辑降级） */
    public ToolExecutionContext(
        String runId,
        int turnNumber,
        PermissionMode permissionMode,
        ApprovalHandler approvalHandler,
        RunRecorder recorder,
        InternalToolRouter internalTools,
        ApprovalGrantCache approvalCache
    ) {
        this(runId, turnNumber, permissionMode, null, approvalHandler, recorder, internalTools, approvalCache);
    }
}
