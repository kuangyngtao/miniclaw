package com.clawkit.tools;

import java.nio.file.Path;
import java.util.Set;

/**
 * 工具执行作用域：run/turn 上下文和工作区边界。
 * 不把 engine 的 PermissionMode 放进 tools 契约。
 */
public record ToolExecutionScope(
    String runId,
    int turnNumber,
    Path workspaceRoot,
    Set<Path> additionalReadRoots
) {
    /** 最小作用域（用于测试或未知上下文） */
    public static ToolExecutionScope minimal(Path workspaceRoot) {
        return new ToolExecutionScope("unknown", 0, workspaceRoot, Set.of());
    }

    /**
     * workspace root 必须是规范化绝对路径。
     */
    public ToolExecutionScope {
        if (workspaceRoot != null) {
            workspaceRoot = workspaceRoot.normalize().toAbsolutePath();
        }
    }
}
