package com.clawkit.reliability;

import com.clawkit.tools.control.TokenBudget;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 共享 token 预算账本（线程安全）。
 *
 * <p>父子 Agent 使用同一账本：子账本每次预留同时从父账本扣除，
 * 子配额（cap）只能小于等于创建时刻父账本的剩余额度约束——
 * 子任务只能得到更小配额，不能扩大父预算。
 */
public final class BudgetLedger implements TokenBudget {

    private final BudgetLedger parent;
    private final AtomicLong remaining;

    private BudgetLedger(BudgetLedger parent, long tokens) {
        this.parent = parent;
        this.remaining = new AtomicLong(tokens);
    }

    /** 根账本。 */
    public static BudgetLedger of(long tokens) {
        if (tokens < 0) throw new IllegalArgumentException("tokens must be >= 0");
        return new BudgetLedger(null, tokens);
    }

    /** 子账本：自身上限 cap，且每次预留仍从父账本扣除（共享池）。 */
    public BudgetLedger childCapped(long cap) {
        if (cap < 0) throw new IllegalArgumentException("cap must be >= 0");
        return new BudgetLedger(this, Math.min(cap, Math.max(0, remaining.get())));
    }

    @Override
    public boolean limited() {
        return true;
    }

    @Override
    public long remaining() {
        long own = remaining.get();
        if (parent == null) return own;
        return Math.min(own, parent.remaining());
    }

    @Override
    public long reserveUpTo(long requested) {
        if (requested <= 0) return 0;
        // 先从自身额度 CAS 预留
        long granted;
        while (true) {
            long current = remaining.get();
            if (current <= 0) return 0;
            granted = Math.min(requested, current);
            if (remaining.compareAndSet(current, current - granted)) break;
        }
        if (parent == null) return granted;
        // 再从父账本预留；父给得更少时退还差额
        long parentGranted = parent.reserveUpTo(granted);
        if (parentGranted < granted) {
            remaining.addAndGet(granted - parentGranted);
        }
        return parentGranted;
    }

    @Override
    public void settle(long reserved, long actual) {
        if (reserved < 0 || actual < 0) return;
        long delta = reserved - actual; // 多退（正）少补（负，可透支为负余额）
        remaining.addAndGet(delta);
        if (parent != null) {
            parent.settle(reserved, actual);
        }
    }
}
