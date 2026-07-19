package com.clawkit.tools;

import com.clawkit.tools.control.ExecutionControl;
import java.nio.file.Path;
import java.util.Set;

/**
 * 工具执行作用域：run/turn 上下文和工作区边界。
 * 不把 engine 的 PermissionMode 放进 tools 契约。
 *
 * <p>P1-G：增加 {@link ExecutionControl}，取消、deadline 和预算随作用域
 * 贯穿到工具实现（如 ProcessRunner）。
 */
public record ToolExecutionScope(
    String runId,
    int turnNumber,
    Path workspaceRoot,
    Set<Path> additionalReadRoots,
    ExecutionControl control
) {
    /** 最小作用域（用于测试或未知上下文） */
    public static ToolExecutionScope minimal(Path workspaceRoot) {
        return new ToolExecutionScope("unknown", 0, workspaceRoot, Set.of(),
            ExecutionControl.none());
    }

    /**
     * workspace root 必须是规范化绝对路径；control 缺省为 none()。
     */
    public ToolExecutionScope {
        if (workspaceRoot != null) {
            workspaceRoot = workspaceRoot.normalize().toAbsolutePath();
        }
        if (control == null) {
            control = ExecutionControl.none();
        }
    }

    /** 兼容构造器（无 ExecutionControl） */
    public ToolExecutionScope(String runId, int turnNumber, Path workspaceRoot,
                              Set<Path> additionalReadRoots) {
        this(runId, turnNumber, workspaceRoot, additionalReadRoots, ExecutionControl.none());
    }
}
