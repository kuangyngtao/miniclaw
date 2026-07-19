package com.clawkit.tools.control;

import java.time.Instant;
import java.util.Optional;

/** 无控制实现：永不取消、无 deadline、无限预算。 */
final class NoneExecutionControl implements ExecutionControl {

    static final NoneExecutionControl INSTANCE = new NoneExecutionControl();

    private NoneExecutionControl() {}

    @Override public boolean isCancelled() { return false; }

    @Override public Optional<Instant> deadline() { return Optional.empty(); }

    @Override public TokenBudget tokenBudget() { return TokenBudget.unlimited(); }

    @Override public CancelRegistration onCancel(Runnable action) {
        return CancelRegistration.noop();
    }
}
