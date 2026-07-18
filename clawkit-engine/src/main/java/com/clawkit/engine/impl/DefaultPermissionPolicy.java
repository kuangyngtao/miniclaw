package com.clawkit.engine.impl;

import com.clawkit.tools.ApprovalGrantCache;
import com.clawkit.tools.PermissionMode;
import com.clawkit.tools.PermissionPolicy;
import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolMetadata;

/**
 * 默认权限策略，实现与 ToolCallExecutor.defaultPermission() 相同的逻辑。
 *
 * <p>此实现放在 engine 模块，供 PlanExecutor 等 engine 内组件使用。
 * 后续可作为 ToolCallExecutor 中降级逻辑的替代。
 */
final class DefaultPermissionPolicy implements PermissionPolicy {

    @Override
    public PermissionDecision evaluate(
        PermissionMode mode,
        ToolMetadata metadata,
        ToolExecutionRequest request,
        ApprovalGrantCache grants
    ) {
        return switch (mode) {
            case PLAN -> metadata.isReadOnly()
                ? PermissionDecision.allow()
                : PermissionDecision.deny("PLAN_BLOCKED",
                    "Tool '" + metadata.name() + "' is not available in PLAN mode.");

            case ASK -> {
                if (metadata.isReadOnly() && !metadata.isApprovalRequired()) {
                    yield PermissionDecision.allow();
                }
                if (grants.isGranted(metadata.name(), metadata.riskLevel(),
                    request.arguments(), metadata.sideEffects())) {
                    yield PermissionDecision.allow();
                }
                yield PermissionDecision.requireApproval(
                    "Write tool or approval-required tool in ASK mode");
            }

            case AUTO -> {
                if (metadata.isApprovalRequired()) {
                    yield PermissionDecision.requireApproval(
                        "Tool requires approval even in AUTO mode");
                }
                yield PermissionDecision.allow();
            }
        };
    }
}
