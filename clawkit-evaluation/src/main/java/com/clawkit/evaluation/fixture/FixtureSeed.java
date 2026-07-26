package com.clawkit.evaluation.fixture;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Deterministic seed-based data generator for business fixture scenarios.
 *
 * <p>M2-1. Uses a fixed seed to produce reproducible account lists,
 * order distributions, and request IDs. Every invocation with the same
 * seed and parameters produces identical output.
 */
public final class FixtureSeed {

    private FixtureSeed() {}

    /**
     * Generate deterministic account snapshots from a case manifest.
     * All accounts start with {@code openingBalanceCents}.
     */
    public static List<BusinessInvariant.AccountSnapshot> generateAccounts(
        BusinessFixtureCase case_) {
        List<BusinessInvariant.AccountSnapshot> accounts = new ArrayList<>();
        for (String id : case_.hotAccountIds()) {
            accounts.add(new BusinessInvariant.AccountSnapshot(
                id, "HOT", case_.accounts().openingBalanceCents(),
                case_.accounts().openingBalanceCents()));
        }
        for (String id : case_.normalAccountIds()) {
            accounts.add(new BusinessInvariant.AccountSnapshot(
                id, "NORMAL", case_.accounts().openingBalanceCents(),
                case_.accounts().openingBalanceCents()));
        }
        return List.copyOf(accounts);
    }

    /**
     * Generate deterministic order snapshots for a load test.
     *
     * @param case_  the case manifest
     * @param totalOrders  total number of orders to simulate
     * @return ordered list of order snapshots
     */
    public static List<BusinessInvariant.OrderSnapshot> generateOrders(
        BusinessFixtureCase case_, int totalOrders) {
        RandomGenerator rng = RandomGeneratorFactory.getDefault().create(case_.seed());
        List<String> hotIds = case_.hotAccountIds();
        List<String> normalIds = case_.normalAccountIds();
        List<BusinessInvariant.OrderSnapshot> orders = new ArrayList<>(totalOrders);

        for (int i = 0; i < totalOrders; i++) {
            // Select account with hot traffic ratio
            String accountId;
            if (rng.nextDouble() < case_.load().hotTrafficRatio()) {
                accountId = hotIds.get(rng.nextInt(hotIds.size()));
            } else {
                accountId = normalIds.get(rng.nextInt(normalIds.size()));
            }

            // Deterministic request ID
            String requestId = String.format("%08x-%04x-%04x-%04x-%012x",
                rng.nextInt(), rng.nextInt(0xFFFF),
                rng.nextInt(0xFFFF) | 0x4000,
                rng.nextInt(0xFFFF) & 0xBFFF | 0x8000,
                rng.nextLong() & 0xFFFFFFFFFFFFL);

            // Random amount: 100..50000 cents ($1..$500)
            long amountCents = 100 + rng.nextLong(49_900);

            orders.add(new BusinessInvariant.OrderSnapshot(
                requestId, accountId, amountCents));
        }

        return List.copyOf(orders);
    }
}
