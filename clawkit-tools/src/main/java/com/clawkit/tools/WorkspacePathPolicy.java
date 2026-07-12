package com.clawkit.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 统一工作区路径安全策略。
 *
 * <p>防御：
 * <ul>
 *   <li>../ 目录遍历</li>
 *   <li>绝对路径逃逸</li>
 *   <li>符号链接指向外部目录</li>
 *   <li>额外只读 root 的写入</li>
 *   <li>TOCTOU（写入前和原子替换前均检查）</li>
 * </ul>
 */
public record WorkspacePathPolicy(Path workDir, Set<Path> additionalReadRoots) {

    /** workspace root 必须在启动时解析为 real path */
    public WorkspacePathPolicy {
        try {
            if (!Files.exists(workDir)) {
                Files.createDirectories(workDir);
            }
            workDir = workDir.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve workspace: " + workDir, e);
        }
        if (additionalReadRoots == null) {
            additionalReadRoots = Set.of();
        }
    }

    public WorkspacePathPolicy(Path workDir) {
        this(workDir, Set.of());
    }

    /**
     * 解析并验证用户提供的路径。
     *
     * @param userPath   用户输入路径
     * @param allowWrite 是否允许写入（写入只能在工作区内）
     * @return 规范化后的真实路径
     * @throws PathEscapeException 路径逃逸
     */
    public Path resolve(String userPath, boolean allowWrite) throws PathEscapeException {
        Path resolved = workDir.resolve(userPath).normalize();

        // 检查目标是否已存在 → 解析真实路径
        if (Files.exists(resolved)) {
            try {
                Path real = resolved.toRealPath();
                if (allowWrite) {
                    if (!real.startsWith(workDir)) {
                        throw new PathEscapeException(userPath, "Symlink escape detected: " + real);
                    }
                } else {
                    // 读操作：允许额外只读 root
                    if (!real.startsWith(workDir) && !isUnderExtraRoot(real)) {
                        throw new PathEscapeException(userPath, "Path outside workspace and extra roots: " + real);
                    }
                }
                return real;
            } catch (IOException e) {
                throw new PathEscapeException(userPath, "Cannot resolve real path: " + e.getMessage());
            }
        }

        // 目标不存在（写场景）→ 检查最近的已存在父目录
        Path parent = resolved;
        while (parent != null && !Files.exists(parent)) {
            parent = parent.getParent();
        }
        if (parent != null) {
            try {
                Path realParent = parent.toRealPath();
                if (!realParent.startsWith(workDir)) {
                    throw new PathEscapeException(userPath,
                        "Parent path outside workspace: " + realParent);
                }
            } catch (IOException e) {
                throw new PathEscapeException(userPath, "Cannot resolve parent real path: " + e.getMessage());
            }
        }

        return resolved;
    }

    /** 要求写入权限：目标必须在 workspace 内 */
    public void requireWriteAccess(Path resolved) throws PathEscapeException {
        if (Files.exists(resolved)) {
            if (Files.isSymbolicLink(resolved)) {
                throw new PathEscapeException(resolved.toString(), "Refusing to write to symlink target");
            }
            try {
                Path real = resolved.toRealPath();
                if (!real.startsWith(workDir)) {
                    throw new PathEscapeException(resolved.toString(), "Write path outside workspace");
                }
            } catch (IOException e) {
                throw new PathEscapeException(resolved.toString(), "Cannot verify write path: " + e.getMessage());
            }
        } else {
            // 新文件：检查最近存在的父目录
            Path parent = resolved.getParent();
            if (parent != null) {
                try {
                    Path realParent = parent.toRealPath();
                    if (!realParent.startsWith(workDir)) {
                        throw new PathEscapeException(resolved.toString(),
                            "Parent path outside workspace: " + realParent);
                    }
                } catch (IOException e) {
                    throw new PathEscapeException(resolved.toString(),
                        "Cannot resolve parent real path: " + e.getMessage());
                }
            }
        }
    }

    public Path getWorkDir() { return workDir; }
    public Path getWorkDirReal() { return workDir; } // already real

    private boolean isUnderExtraRoot(Path real) {
        for (Path root : additionalReadRoots) {
            if (real.startsWith(root)) return true;
        }
        return false;
    }

    // ── exception class ───────────────────────────────────────────

    public static class PathEscapeException extends Exception {
        public PathEscapeException(String path, String reason) {
            super("Path escape: '" + path + "' — " + reason);
        }
    }
}
