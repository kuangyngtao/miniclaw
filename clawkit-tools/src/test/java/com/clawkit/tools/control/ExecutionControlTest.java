package com.clawkit.tools.control;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionControlTest {

    /** 可编程的测试控制实现 */
    private static class FakeControl implements ExecutionControl {
        boolean cancelled;
        Instant deadline;
        TokenBudget budget = TokenBudget.unlimited();
        @Override public boolean isCancelled() { return cancelled; }
        @Override public Optional<Instant> deadline() { return Optional.ofNullable(deadline); }
        @Override public TokenBudget tokenBudget() { return budget; }
        @Override public CancelRegistration onCancel(Runnable action) {
            if (cancelled) action.run();
            return CancelRegistration.noop();
        }
    }

    private static class FixedBudget implements TokenBudget {
        long remaining;
        FixedBudget(long remaining) { this.remaining = remaining; }
        @Override public boolean limited() { return true; }
        @Override public long remaining() { return remaining; }
        @Override public long reserveUpTo(long requested) {
            long granted = Math.min(requested, Math.max(0, remaining));
            remaining -= granted;
            return granted;
        }
        @Override public void settle(long reserved, long actual) {
            remaining += reserved - actual;
        }
    }

    @Test
    void noneControlNeverHalts() {
        ExecutionControl none = ExecutionControl.none();
        assertFalse(none.isCancelled());
        assertTrue(none.deadline().isEmpty());
        assertTrue(none.remainingTime().isEmpty());
        assertFalse(none.tokenBudget().limited());
        assertDoesNotThrow(none::checkpoint);
        // onCancel 永不触发
        AtomicLong fired = new AtomicLong();
        try (CancelRegistration reg = none.onCancel(fired::incrementAndGet)) {
            assertEquals(0, fired.get());
        }
    }

    @Test
    void checkpointThrowsCancelledFirst() {
        FakeControl c = new FakeControl();
        c.cancelled = true;
        c.deadline = Instant.now().minusSeconds(10); // 同时超 deadline
        ExecutionHaltedException ex = assertThrows(ExecutionHaltedException.class, c::checkpoint);
        assertEquals(ExecutionHaltedException.Reason.CANCELLED, ex.reason());
    }

    @Test
    void checkpointThrowsOnDeadlineExceeded() {
        FakeControl c = new FakeControl();
        c.deadline = Instant.now().minus(Duration.ofSeconds(1));
        ExecutionHaltedException ex = assertThrows(ExecutionHaltedException.class, c::checkpoint);
        assertEquals(ExecutionHaltedException.Reason.DEADLINE_EXCEEDED, ex.reason());
    }

    @Test
    void checkpointThrowsOnBudgetExhausted() {
        FakeControl c = new FakeControl();
        c.budget = new FixedBudget(0);
        ExecutionHaltedException ex = assertThrows(ExecutionHaltedException.class, c::checkpoint);
        assertEquals(ExecutionHaltedException.Reason.BUDGET_EXHAUSTED, ex.reason());
    }

    @Test
    void checkpointPassesWithinLimits() {
        FakeControl c = new FakeControl();
        c.deadline = Instant.now().plus(Duration.ofMinutes(5));
        c.budget = new FixedBudget(100);
        assertDoesNotThrow(c::checkpoint);
    }

    @Test
    void unlimitedBudgetNeverExhausted() {
        TokenBudget b = TokenBudget.unlimited();
        assertFalse(b.limited());
        assertFalse(b.exhausted());
        assertEquals(Long.MAX_VALUE, b.remaining());
        assertEquals(50, b.reserveUpTo(50));
        b.settle(50, 500); // no-op
        assertEquals(Long.MAX_VALUE, b.remaining());
    }

    @Test
    void onCancelFiresImmediatelyWhenAlreadyCancelled() {
        FakeControl c = new FakeControl();
        c.cancelled = true;
        AtomicLong fired = new AtomicLong();
        try (CancelRegistration reg = c.onCancel(fired::incrementAndGet)) {
            assertEquals(1, fired.get());
        }
    }
}
