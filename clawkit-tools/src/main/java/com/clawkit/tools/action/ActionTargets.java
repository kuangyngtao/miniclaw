package com.clawkit.tools.action;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 规范化目标标识：同一资源在不同工具/参数下必须得到同一 canonicalTarget，
 * 目标互斥才有意义。
 */
public final class ActionTargets {

    private static final boolean WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private ActionTargets() {}

    /** 文件目标："file:" + 规范化绝对路径（Windows 下统一小写和分隔符）。 */
    public static String fileTarget(Path path) {
        String p = path.toAbsolutePath().normalize().toString();
        if (WINDOWS) {
            p = p.replace('\\', '/').toLowerCase(Locale.ROOT);
        }
        return "file:" + p;
    }

    /** 任意 Shell 目标：以工作区为互斥边界（任意命令的影响范围不可静态窄化）。 */
    public static String shellTarget(Path workspaceRoot) {
        String p = workspaceRoot != null
            ? workspaceRoot.toAbsolutePath().normalize().toString()
            : "unknown";
        if (WINDOWS) {
            p = p.replace('\\', '/').toLowerCase(Locale.ROOT);
        }
        return "shell:" + p;
    }

    /** MCP 写工具目标：按 server + tool 串行化（远端资源无法静态识别时的保守边界）。 */
    public static String mcpTarget(String serverName, String toolName) {
        return "mcp:" + serverName + ":" + toolName;
    }

    /** 引擎内部状态目标。 */
    public static String internalTarget(String name) {
        return "internal:" + name;
    }
}
