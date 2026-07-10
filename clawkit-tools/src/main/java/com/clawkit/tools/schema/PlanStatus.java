package com.clawkit.tools.schema;

/**
 * 计划的生命周期状态。
 * 状态机：CREATED → RUNNING → COMPLETED / FAILED / CANCELLED
 */
public enum PlanStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
