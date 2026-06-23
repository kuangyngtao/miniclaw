package com.miniclaw.tools.schema;

/**
 * 单个任务的执行状态。
 * 状态机：PENDING → RUNNING → COMPLETED / FAILED / SKIPPED
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED
}
