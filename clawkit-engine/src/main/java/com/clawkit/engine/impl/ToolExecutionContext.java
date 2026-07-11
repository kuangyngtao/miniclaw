package com.clawkit.engine.impl;

import com.clawkit.engine.ApprovalHandler;
import com.clawkit.engine.PermissionMode;
import com.clawkit.observability.RunRecorder;

import java.util.Set;

/**
 * 工具执行上下文，将 engine 侧信息传递给 ToolCallExecutor。
 */
public record ToolExecutionContext(
    String runId,
    int turnNumber,
    PermissionMode permissionMode,
    ApprovalHandler approvalHandler,
    RunRecorder recorder,
    InternalToolRouter internalTools,
    Set<String> autoApprovedTools
) {}
