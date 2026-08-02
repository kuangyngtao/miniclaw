package com.clawkit.tools.control;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Simple {@link ExecutionControl} that enforces only a deadline.
 * Never cancelled, unlimited token budget.
 *
 * <p>Useful for MCP transport-level timeouts where full cancellation-tree
 * integration is not needed.
 */
public final class TimeoutExecutionControl implements ExecutionControl {

    private final Instant deadline;

    public TimeoutExecutionControl(Duration timeout) {
        this(Instant.now().plus(timeout));
    }

    public TimeoutExecutionControl(Instant deadline) {
        this.deadline = deadline;
    }

    @Override
    public boolean isCancelled() { return false; }

    @Override
    public Optional<Instant> deadline() { return Optional.of(deadline); }

    @Override
    public TokenBudget tokenBudget() { return TokenBudget.unlimited(); }

    @Override
    public CancelRegistration onCancel(Runnable action) {
        return () -> {}; // never cancelled, no-op
    }

    @Override
    public String toString() {
        return "TimeoutExecutionControl{deadline=" + deadline + "}";
    }
}
