package com.clawkit.evaluation.fixture;

import java.util.List;
import java.util.Set;

/**
 * Versioned business fixture case manifest.
 *
 * <p>M2-1. Defines the deterministic contract for a business-data-driven
 * incident scenario. The manifest is NEVER readable by opsro, Discovery,
 * DeepSeek, reports, or Feishu — it lives in the fixture-admin control plane.
 *
 * <p>Only two cases are supported: {@code LOCK_INJECTED_V1} and
 * {@code HOT_ACCOUNT_CONTENTION_V1}.
 */
public record BusinessFixtureCase(
    String caseVersion,
    CaseType caseType,
    long seed,
    AccountDistribution accounts,
    LoadParameters load,
    DbPoolConfig pool,
    ReconciliationConfig reconciliation,
    Set<String> expectedDegradationSignals
) {
    public enum CaseType {
        LOCK_INJECTED_V1,
        HOT_ACCOUNT_CONTENTION_V1
    }

    /** Deterministic account layout. */
    public record AccountDistribution(
        int hotAccountCount,
        int normalAccountCount,
        long openingBalanceCents
    ) {
        public AccountDistribution {
            if (hotAccountCount < 1) throw new IllegalArgumentException("at least 1 hot account");
            if (normalAccountCount < 1) throw new IllegalArgumentException("at least 1 normal account");
            if (openingBalanceCents < 1) throw new IllegalArgumentException("positive opening balance");
        }

        public int totalAccounts() { return hotAccountCount + normalAccountCount; }
    }

    /** Traffic generation parameters. */
    public record LoadParameters(
        double hotTrafficRatio,
        int requestsPerSecond,
        int durationSeconds,
        int maxVus
    ) {
        public LoadParameters {
            if (hotTrafficRatio < 0 || hotTrafficRatio > 1)
                throw new IllegalArgumentException("hotTrafficRatio in [0,1]");
            if (requestsPerSecond < 1) throw new IllegalArgumentException("requestsPerSecond >= 1");
            if (durationSeconds < 1) throw new IllegalArgumentException("durationSeconds >= 1");
            if (maxVus < 1) throw new IllegalArgumentException("maxVus >= 1");
        }
    }

    /** Database connection pool configuration. */
    public record DbPoolConfig(
        int maxPoolSize,
        long connectionTimeoutMs
    ) {
        public DbPoolConfig {
            if (maxPoolSize < 2) throw new IllegalArgumentException("maxPoolSize >= 2");
            if (connectionTimeoutMs < 100) throw new IllegalArgumentException("connectionTimeoutMs >= 100");
        }
    }

    /** Reconciliation task parameters. */
    public record ReconciliationConfig(
        int intervalSeconds,
        int lockHoldSeconds
    ) {
        public ReconciliationConfig {
            if (intervalSeconds < 0) throw new IllegalArgumentException("intervalSeconds >= 0");
            if (lockHoldSeconds < 0) throw new IllegalArgumentException("lockHoldSeconds >= 0");
        }

        public boolean enabled() { return intervalSeconds > 0; }
    }

    // ── Pre-built manifests ──

    /** Default HOT_ACCOUNT_CONTENTION_V1 manifest with frozen parameters. */
    public static final BusinessFixtureCase HOT_ACCOUNT_CONTENTION_V1 = new BusinessFixtureCase(
        "HOT_ACCOUNT_CONTENTION_V1",
        CaseType.HOT_ACCOUNT_CONTENTION_V1,
        0x0A2E_2026_0726_0001L,
        new AccountDistribution(1, 99, 1_000_000_00L), // $10,000 each
        new LoadParameters(0.85, 20, 90, 40),
        new DbPoolConfig(6, 800),
        new ReconciliationConfig(3, 2),
        Set.of("lock_wait", "connection_pending", "p95_degraded", "error_rate_elevated")
    );

    /** Default LOCK_INJECTED_V1 manifest. */
    public static final BusinessFixtureCase LOCK_INJECTED_V1 = new BusinessFixtureCase(
        "LOCK_INJECTED_V1",
        CaseType.LOCK_INJECTED_V1,
        0x0A2E_2026_0726_0001L,
        new AccountDistribution(1, 99, 1_000_000_00L),
        new LoadParameters(0.01, 4, 30, 8),
        new DbPoolConfig(6, 800),
        new ReconciliationConfig(0, 0), // no reconciliation — lock injected via control
        Set.of("lock_wait", "connection_pending")
    );

    /**
     * Compute the deterministic account IDs for this case.
     * Account IDs are: hot-0001 .. hot-{N}, acct-0001 .. acct-{M}.
     */
    public List<String> hotAccountIds() {
        return java.util.stream.IntStream.rangeClosed(1, accounts.hotAccountCount())
            .mapToObj(i -> String.format("hot-%04d", i))
            .toList();
    }

    public List<String> normalAccountIds() {
        return java.util.stream.IntStream.rangeClosed(1, accounts.normalAccountCount())
            .mapToObj(i -> String.format("acct-%04d", i))
            .toList();
    }

    public List<String> allAccountIds() {
        var all = new java.util.ArrayList<String>();
        all.addAll(hotAccountIds());
        all.addAll(normalAccountIds());
        return List.copyOf(all);
    }
}
