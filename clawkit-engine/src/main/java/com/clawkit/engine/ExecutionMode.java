package com.clawkit.engine;

/**
 * Agent 执行模式。
 * REACT: 当前 ReAct 循环（默认）。
 * PLAN_EXECUTE: 先由 LLM 规划任务 DAG，经用户确认后逐级执行。
 */
public enum ExecutionMode {
    REACT,
    PLAN_EXECUTE
}
