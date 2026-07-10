package com.clawkit.tools;

/**
 * 工具副作用类型，用于审批和审计分类。
 */
public enum ToolSideEffect {
    FILE_WRITE,
    FILE_DELETE,
    SHELL_EXEC,
    NETWORK_OUT,
    MESSAGE_SEND
}
