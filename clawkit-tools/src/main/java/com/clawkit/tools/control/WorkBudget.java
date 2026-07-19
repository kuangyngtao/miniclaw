package com.clawkit.tools.control;

/**
 * Non-token execution budgets shared by a run tree.
 *
 * <p>Each successful acquisition authorizes exactly one new logical Provider
 * or Tool invocation. Implementations must be thread safe.
 */
public interface WorkBudget {

    boolean tryAcquireProviderCall();

    boolean tryAcquireToolCall();

    long remainingProviderCalls();

    long remainingToolCalls();

    static WorkBudget unlimited() {
        return UnlimitedWorkBudget.INSTANCE;
    }
}

final class UnlimitedWorkBudget implements WorkBudget {
    static final UnlimitedWorkBudget INSTANCE = new UnlimitedWorkBudget();
    private UnlimitedWorkBudget() {}
    @Override public boolean tryAcquireProviderCall() { return true; }
    @Override public boolean tryAcquireToolCall() { return true; }
    @Override public long remainingProviderCalls() { return Long.MAX_VALUE; }
    @Override public long remainingToolCalls() { return Long.MAX_VALUE; }
}
