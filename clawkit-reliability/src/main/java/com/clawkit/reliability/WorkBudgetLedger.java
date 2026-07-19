package com.clawkit.reliability;

import com.clawkit.tools.control.WorkBudget;
import java.util.concurrent.atomic.AtomicLong;

/** Shared, thread-safe Provider/Tool call budget. */
public final class WorkBudgetLedger implements WorkBudget {

    private final AtomicLong providerCalls;
    private final AtomicLong toolCalls;

    private WorkBudgetLedger(long providerCalls, long toolCalls) {
        if (providerCalls < 0 || toolCalls < 0) {
            throw new IllegalArgumentException("call budgets must be >= 0");
        }
        this.providerCalls = new AtomicLong(providerCalls);
        this.toolCalls = new AtomicLong(toolCalls);
    }

    public static WorkBudgetLedger of(long providerCalls, long toolCalls) {
        return new WorkBudgetLedger(providerCalls, toolCalls);
    }

    @Override
    public boolean tryAcquireProviderCall() {
        return tryAcquire(providerCalls);
    }

    @Override
    public boolean tryAcquireToolCall() {
        return tryAcquire(toolCalls);
    }

    @Override
    public long remainingProviderCalls() {
        return Math.max(0, providerCalls.get());
    }

    @Override
    public long remainingToolCalls() {
        return Math.max(0, toolCalls.get());
    }

    private static boolean tryAcquire(AtomicLong counter) {
        while (true) {
            long current = counter.get();
            if (current <= 0) return false;
            if (counter.compareAndSet(current, current - 1)) return true;
        }
    }
}
