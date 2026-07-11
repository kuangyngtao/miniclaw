package com.clawkit.observability;

/**
 * Run 终止状态。
 * 对应 AgentEngine.run() 的 6 个 exit point。
 */
public enum RunStatus {
    /** 正常完成：模型输出最终回复 */
    COMPLETED,
    /** 用户中断：Ctrl+C */
    INTERRUPTED,
    /** 达到最大轮次硬上限 */
    HARD_LIMIT,
    /** compact 后仍超过 95% 硬限制 */
    COMPACT_FAILED,
    /** 慢思考规划阶段 LLM 调用失败 */
    PLANNING_ERROR,
    /** LLM 调用失败 */
    LLM_ERROR,
    /** Plan-Execute 模式：计划被拒绝 */
    PLAN_REJECTED,
    /** Plan-Execute 模式：计划解析失败 */
    PLAN_PARSE_ERROR,
    /** Plan-Execute 模式：执行失败 */
    EXECUTION_FAILED,
    /** 任务被安全拦截器阻止 */
    SAFETY_BLOCKED,
    /** 未知错误 */
    UNKNOWN_ERROR,
    /** run 仍在执行中（初始状态） */
    RUNNING,
    /** recorder 关闭时 run 仍未完成 */
    INCOMPLETE
}
