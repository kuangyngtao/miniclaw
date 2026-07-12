package com.clawkit.tools;

/**
 * 权限模式。
 * 从 engine 模块迁移至 tools 模块，避免 tools 反向依赖 engine。
 */
public enum PermissionMode {
    /** 自动执行所有允许的操作（仍受 SafetyInterceptor 约束） */
    AUTO,
    /** 写操作、高风险或要求审批的工具执行前请求人工决定 */
    ASK,
    /** 只暴露和执行只读工具 */
    PLAN
}
