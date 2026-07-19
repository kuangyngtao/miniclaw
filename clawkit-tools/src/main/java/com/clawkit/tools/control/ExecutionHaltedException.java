package com.clawkit.tools.control;

/**
 * 执行被控制面停止：取消、超过 deadline 或预算耗尽。
 *
 * <p>由 {@link ExecutionControl#checkpoint()} 抛出；调用方按 {@link Reason}
 * 映射到结构化终态，不得把该异常当作普通工具/Provider 失败重试。
 */
public final class ExecutionHaltedException extends RuntimeException {

    public enum Reason { CANCELLED, DEADLINE_EXCEEDED, BUDGET_EXHAUSTED }

    private final Reason reason;

    public ExecutionHaltedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
