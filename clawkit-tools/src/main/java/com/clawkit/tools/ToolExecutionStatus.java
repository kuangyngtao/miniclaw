package com.clawkit.tools;

/**
 * 工具调用的唯一终态。
 * 替代旧 {@code boolean error + boolean timedOut} 的矛盾组合。
 */
public enum ToolExecutionStatus {
    /** 成功完成 */
    SUCCESS,
    /** 用户拒绝 */
    REJECTED,
    /** 安全策略或拦截器阻断 */
    BLOCKED,
    /** 参数不合法（JSON 解析失败或 schema 校验失败） */
    INVALID_ARGUMENTS,
    /** 工具未找到 */
    NOT_FOUND,
    /** 执行超时 */
    TIMED_OUT,
    /** 被取消 */
    CANCELLED,
    /** 非零退出码（进程类工具） */
    NON_ZERO_EXIT,
    /** 工具自身报告的错误 */
    TOOL_ERROR,
    /** 内部错误（执行器异常、配置错误等） */
    INTERNAL_ERROR;

    /** 是否为成功的终态 */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /** 是否为失败的终态 */
    public boolean isFailure() {
        return this != SUCCESS;
    }
}
