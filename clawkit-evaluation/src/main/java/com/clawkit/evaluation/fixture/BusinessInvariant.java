package com.clawkit.evaluation.fixture;

import java.util.List;
import java.util.Map;

/**
 * Deterministic business invariant for account balance conservation.
 *
 * <p>M2-1. The fundamental invariant:
 * <pre>{@code
 * opening_balance_cents = balance_cents + SUM(deduped successful orders.amount_cents)
 * }</pre>
 * Must hold per-account, not just aggregate. Also verifies idempotency
 * (duplicate requestIds do not double-deduct) and transaction atomicity.
 */
public final class BusinessInvariant {

    private BusinessInvariant() {}

    /** Snapshot of account state at a point in time. */
    public record AccountSnapshot(
        String accountId,
        String accountClass,
        long openingBalanceCents,
        long balanceCents
    ) {}

    /** Snapshot of a single order. */
    public record OrderSnapshot(
        String requestId,
        String accountId,
        long amountCents
    ) {}

    /** Result of invariant verification. */
    public record InvariantResult(
        boolean passed,
        String accountId,
        long openingBalance,
        long currentBalance,
        long totalDedupedOrders,
        long expectedBalance
    ) {
        public long drift() { return expectedBalance - currentBalance; }
    }

    /**
     * Verify the conservation invariant for a single account.
     *
     * @param account     the account snapshot
     * @param allOrders   all orders (may include duplicates; deduped by requestId)
     * @return verification result
     */
    public static InvariantResult verify(AccountSnapshot account,
                                         List<OrderSnapshot> allOrders) {
        var deduped = allOrders.stream()
            .filter(o -> o.accountId().equals(account.accountId()))
            .collect(java.util.stream.Collectors.toMap(
                OrderSnapshot::requestId, o -> o, (a, b) -> a))
            .values();

        long totalDeduped = deduped.stream().mapToLong(OrderSnapshot::amountCents).sum();
        long expectedBalance = account.openingBalanceCents() - totalDeduped;

        return new InvariantResult(
            expectedBalance == account.balanceCents(),
            account.accountId(),
            account.openingBalanceCents(),
            account.balanceCents(),
            totalDeduped,
            expectedBalance
        );
    }

    /**
     * Verify all accounts in a batch.
     *
     * @return map of accountId → result
     */
    public static Map<String, InvariantResult> verifyAll(
        List<AccountSnapshot> accounts,
        List<OrderSnapshot> orders
    ) {
        return accounts.stream()
            .map(a -> verify(a, orders))
            .collect(java.util.stream.Collectors.toMap(
                InvariantResult::accountId, r -> r));
    }
}
