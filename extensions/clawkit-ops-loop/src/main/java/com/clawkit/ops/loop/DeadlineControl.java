package com.clawkit.ops.loop;

import com.clawkit.tools.control.CancelRegistration;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.control.ExecutionHaltedException;
import com.clawkit.tools.control.TokenBudget;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Simple deadline-based ExecutionControl for MCP requests. */
final class DeadlineControl implements ExecutionControl {
    private final Instant deadline;

    DeadlineControl(Duration timeout, Instant now) {
        this.deadline = now.plus(timeout);
    }

    @Override public boolean isCancelled() { return false; }
    @Override public Optional<Instant> deadline() { return Optional.of(deadline); }
    @Override public TokenBudget tokenBudget() { return TokenBudget.unlimited(); }

    @Override
    public void checkpoint() throws ExecutionHaltedException {
        if (Instant.now().isAfter(deadline)) {
            throw new ExecutionHaltedException(
                ExecutionHaltedException.Reason.DEADLINE_EXCEEDED,
                "deadline exceeded at " + deadline);
        }
    }

    @Override
    public CancelRegistration onCancel(Runnable action) {
        return () -> {}; // deadline-only, no cancel support
    }
}
