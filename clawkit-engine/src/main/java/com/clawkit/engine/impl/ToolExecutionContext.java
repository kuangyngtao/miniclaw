package com.clawkit.engine.impl;

import com.clawkit.engine.ApprovalHandler;
import com.clawkit.observability.RunRecorder;
import com.clawkit.tools.ApprovalGrantCache;
import com.clawkit.tools.PermissionMode;
import com.clawkit.tools.PermissionPolicy;
import com.clawkit.tools.control.ExecutionControl;

/**
 * 工具执行上下文，将 engine 侧信息传递给 ToolCallExecutor。
 *
 * <p>V2：使用 tools 模块的 PermissionMode/PermissionPolicy/ApprovalGrantCache。
 * PermissionPolicy 为必需参数，不得为 null。
 *
 * <p>P1-G：携带 {@link ExecutionControl}，取消/deadline/预算贯穿工具执行。
 */
public record ToolExecutionContext(
    String runId,
    int turnNumber,
    PermissionMode permissionMode,
    PermissionPolicy permissionPolicy,
    ApprovalHandler approvalHandler,
    RunRecorder recorder,
    InternalToolRouter internalTools,
    ApprovalGrantCache approvalCache,
    ExecutionControl control
) {
    public ToolExecutionContext {
        if (permissionPolicy == null) {
            throw new NullPointerException("permissionPolicy must not be null");
        }
        if (control == null) {
            control = ExecutionControl.none();
        }
    }

    /** 兼容构造器（无 ExecutionControl） */
    public ToolExecutionContext(
        String runId, int turnNumber, PermissionMode permissionMode,
        PermissionPolicy permissionPolicy, ApprovalHandler approvalHandler,
        RunRecorder recorder, InternalToolRouter internalTools,
        ApprovalGrantCache approvalCache
    ) {
        this(runId, turnNumber, permissionMode, permissionPolicy, approvalHandler,
            recorder, internalTools, approvalCache, ExecutionControl.none());
    }
}
