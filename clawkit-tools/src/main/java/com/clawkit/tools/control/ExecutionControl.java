package com.clawkit.tools.control;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 执行控制句柄：取消信号、deadline 和共享 token 预算的统一载体（P1-G0 契约）。
 *
 * <p>Provider 调用、工具执行、compact、memory hooks 在发起工作前必须执行
 * {@link #checkpoint()}；长阻塞操作应通过 {@link #onCancel(Runnable)} 注册
 * 中断动作（中断线程、终止进程树、取消 HTTP future）。
 *
 * <p>实现要求：{@code isCancelled()} 一旦为 true 不得复位；父控制取消必须
 * 级联到全部子控制。无控制语境使用 {@link #none()}（永不取消、无限预算）。
 */
public interface ExecutionControl {

    /** 是否已被取消（含父级联取消）。一旦 true 永不复位。 */
    boolean isCancelled();

    /** 绝对截止时间；empty 表示无 deadline。 */
    Optional<Instant> deadline();

    /** 距 deadline 的剩余时间；empty 表示无 deadline。可能为负。 */
    default Optional<Duration> remainingTime() {
        return deadline().map(d -> Duration.between(Instant.now(), d));
    }

    /** 共享 token 预算句柄，永不为 null（无限制时返回 unlimited 实例）。 */
    TokenBudget tokenBudget();

    /** Shared Provider/Tool call budgets. */
    default WorkBudget workBudget() {
        return WorkBudget.unlimited();
    }

    /** Authorize one new logical Provider call. */
    default void acquireProviderCall() {
        checkpoint();
        if (!workBudget().tryAcquireProviderCall()) {
            throw new ExecutionHaltedException(
                ExecutionHaltedException.Reason.BUDGET_EXHAUSTED,
                "provider call budget exhausted");
        }
    }

    /** Authorize one new Tool call. */
    default void acquireToolCall() {
        checkpoint();
        if (!workBudget().tryAcquireToolCall()) {
            throw new ExecutionHaltedException(
                ExecutionHaltedException.Reason.BUDGET_EXHAUSTED,
                "tool call budget exhausted");
        }
    }

    /**
     * 检查点：已取消 / 已过 deadline / 预算耗尽时抛出 {@link ExecutionHaltedException}。
     * 所有 Provider、工具、compact、memory hook 调用点在发起前必须调用。
     */
    default void checkpoint() {
        if (isCancelled()) {
            throw new ExecutionHaltedException(
                ExecutionHaltedException.Reason.CANCELLED, "execution cancelled");
        }
        Optional<Duration> rt = remainingTime();
        if (rt.isPresent() && (rt.get().isNegative() || rt.get().isZero())) {
            throw new ExecutionHaltedException(
                ExecutionHaltedException.Reason.DEADLINE_EXCEEDED,
                "deadline exceeded at " + deadline().orElse(null));
        }
        if (tokenBudget().exhausted()) {
            throw new ExecutionHaltedException(
                ExecutionHaltedException.Reason.BUDGET_EXHAUSTED,
                "token budget exhausted");
        }
    }

    /**
     * 注册取消回调；若注册时已取消则立即在调用线程执行一次。
     * 返回的 registration 必须在阻塞操作结束后关闭，防止泄漏和迟到中断。
     */
    CancelRegistration onCancel(Runnable action);

    /** 无控制单例：永不取消、无 deadline、无限预算。 */
    static ExecutionControl none() {
        return NoneExecutionControl.INSTANCE;
    }
}
