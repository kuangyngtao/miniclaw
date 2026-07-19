package com.clawkit.tools.control;

/**
 * Token 预算句柄：调用前预留、按真实用量结算（P1-G0 契约）。
 *
 * <p>父子共享同一账本时，子预算只能获得不大于父剩余的配额；
 * 实现必须线程安全。
 */
public interface TokenBudget {

    /** 是否有限额；false 表示无限预算。 */
    boolean limited();

    /** 剩余 token；无限预算返回 {@link Long#MAX_VALUE}。 */
    long remaining();

    /** 预算是否耗尽（仅在有限额时可能为 true）。 */
    default boolean exhausted() {
        return limited() && remaining() <= 0;
    }

    /**
     * 预留至多 requested 个 token，返回实际预留量（0 ≤ granted ≤ requested）。
     * 剩余为 0 时返回 0。
     */
    long reserveUpTo(long requested);

    /**
     * 按真实用量结算此前的预留：多退少补。
     * actual 超出 reserved 的部分继续从剩余额度扣除（可为负透支，由 checkpoint 拦截后续调用）。
     */
    void settle(long reserved, long actual);

    /** 无限预算单例。 */
    static TokenBudget unlimited() {
        return UnlimitedTokenBudget.INSTANCE;
    }
}

final class UnlimitedTokenBudget implements TokenBudget {
    static final UnlimitedTokenBudget INSTANCE = new UnlimitedTokenBudget();
    private UnlimitedTokenBudget() {}
    @Override public boolean limited() { return false; }
    @Override public long remaining() { return Long.MAX_VALUE; }
    @Override public long reserveUpTo(long requested) { return Math.max(0, requested); }
    @Override public void settle(long reserved, long actual) {}
}
