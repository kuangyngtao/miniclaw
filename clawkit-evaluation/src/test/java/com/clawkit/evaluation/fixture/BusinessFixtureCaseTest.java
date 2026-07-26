package com.clawkit.evaluation.fixture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessFixtureCaseTest {

    // ── Case Manifest ──

    @Test void hotAccountContentionManifestIsValid() {
        var c = BusinessFixtureCase.HOT_ACCOUNT_CONTENTION_V1;
        assertThat(c.caseType()).isEqualTo(BusinessFixtureCase.CaseType.HOT_ACCOUNT_CONTENTION_V1);
        assertThat(c.accounts().hotAccountCount()).isEqualTo(1);
        assertThat(c.accounts().normalAccountCount()).isEqualTo(99);
        assertThat(c.load().hotTrafficRatio()).isEqualTo(0.85);
        assertThat(c.load().requestsPerSecond()).isEqualTo(20);
        assertThat(c.pool().maxPoolSize()).isEqualTo(6);
        assertThat(c.reconciliation().intervalSeconds()).isEqualTo(3);
        assertThat(c.reconciliation().lockHoldSeconds()).isEqualTo(2);
    }

    @Test void lockInjectedManifestIsValid() {
        var c = BusinessFixtureCase.LOCK_INJECTED_V1;
        assertThat(c.caseType()).isEqualTo(BusinessFixtureCase.CaseType.LOCK_INJECTED_V1);
        assertThat(c.reconciliation().enabled()).isFalse();
        assertThat(c.reconciliation().intervalSeconds()).isEqualTo(0);
        assertThat(c.expectedDegradationSignals()).contains("lock_wait");
    }

    @Test void accountIdsAreDeterministic() {
        var c = BusinessFixtureCase.HOT_ACCOUNT_CONTENTION_V1;
        assertThat(c.hotAccountIds()).containsExactly("hot-0001");
        assertThat(c.normalAccountIds()).hasSize(99);
        assertThat(c.normalAccountIds().get(0)).isEqualTo("acct-0001");
        assertThat(c.normalAccountIds().get(98)).isEqualTo("acct-0099");
        assertThat(c.allAccountIds()).hasSize(100);
    }

    // ── Seed determinism ──

    @Test void sameSeedProducesSameAccounts() {
        var a1 = FixtureSeed.generateAccounts(BusinessFixtureCase.HOT_ACCOUNT_CONTENTION_V1);
        var a2 = FixtureSeed.generateAccounts(BusinessFixtureCase.HOT_ACCOUNT_CONTENTION_V1);
        assertThat(a1).containsExactlyElementsOf(a2);
    }

    @Test void sameSeedProducesSameOrders() {
        var o1 = FixtureSeed.generateOrders(BusinessFixtureCase.HOT_ACCOUNT_CONTENTION_V1, 100);
        var o2 = FixtureSeed.generateOrders(BusinessFixtureCase.HOT_ACCOUNT_CONTENTION_V1, 100);
        assertThat(o1).containsExactlyElementsOf(o2);
    }

    @Test void differentCaseProducesDifferentData() {
        var hot = FixtureSeed.generateOrders(BusinessFixtureCase.HOT_ACCOUNT_CONTENTION_V1, 100);
        var lock = FixtureSeed.generateOrders(BusinessFixtureCase.LOCK_INJECTED_V1, 100);
        // Different traffic ratios produce different account distributions
        long hotOnHot = hot.stream().filter(o -> o.accountId().equals("hot-0001")).count();
        long lockOnHot = lock.stream().filter(o -> o.accountId().equals("hot-0001")).count();
        assertThat(hotOnHot).isGreaterThan(lockOnHot);
    }

    @Test void seedIsRepeatable() {
        var o1 = FixtureSeed.generateOrders(BusinessFixtureCase.HOT_ACCOUNT_CONTENTION_V1, 50);
        var o2 = FixtureSeed.generateOrders(BusinessFixtureCase.HOT_ACCOUNT_CONTENTION_V1, 50);
        assertThat(o1).containsExactlyElementsOf(o2);
    }

    // ── Business invariant ──

    @Test void invariantPassesForCleanAccount() {
        var account = new BusinessInvariant.AccountSnapshot(
            "hot-0001", "HOT", 1_000_000_00L, 1_000_000_00L);
        var result = BusinessInvariant.verify(account, List.of());
        assertThat(result.passed()).isTrue();
        assertThat(result.drift()).isEqualTo(0);
    }

    @Test void invariantPassesAfterDeductedOrders() {
        var account = new BusinessInvariant.AccountSnapshot(
            "hot-0001", "HOT", 1_000_000_00L, 999_987_50L);
        var orders = List.of(
            new BusinessInvariant.OrderSnapshot("req-1", "hot-0001", 1000),
            new BusinessInvariant.OrderSnapshot("req-2", "hot-0001", 250));
        var result = BusinessInvariant.verify(account, orders);
        assertThat(result.passed()).isTrue();
        assertThat(result.drift()).isEqualTo(0);
    }

    @Test void invariantDetectsDrift() {
        var account = new BusinessInvariant.AccountSnapshot(
            "hot-0001", "HOT", 1_000_000_00L, 999_990_00L); // missing 1000
        var orders = List.of(
            new BusinessInvariant.OrderSnapshot("req-1", "hot-0001", 500)); // only 500 accounted
        var result = BusinessInvariant.verify(account, orders);
        assertThat(result.passed()).isFalse();
        assertThat(result.drift()).isEqualTo(500);
    }

    @Test void duplicateRequestIdIsDeduped() {
        var account = new BusinessInvariant.AccountSnapshot(
            "hot-0001", "HOT", 1000, 900);
        // Two orders with same requestId — only counted once
        var orders = List.of(
            new BusinessInvariant.OrderSnapshot("dup-1", "hot-0001", 100),
            new BusinessInvariant.OrderSnapshot("dup-1", "hot-0001", 100));
        var result = BusinessInvariant.verify(account, orders);
        assertThat(result.passed()).isTrue();
        assertThat(result.totalDedupedOrders()).isEqualTo(100);
    }

    @Test void invariantIsPerAccount() {
        var hotAcct = new BusinessInvariant.AccountSnapshot(
            "hot-0001", "HOT", 500, 400);
        var normalAcct = new BusinessInvariant.AccountSnapshot(
            "acct-0001", "NORMAL", 500, 500);
        var orders = List.of(
            new BusinessInvariant.OrderSnapshot("r1", "hot-0001", 100));
        // hot-0001 should be balanced, acct-0001 untouched
        assertThat(BusinessInvariant.verify(hotAcct, orders).passed()).isTrue();
        assertThat(BusinessInvariant.verify(normalAcct, orders).passed()).isTrue();
    }

    @Test void verifyAllAggregatesCorrectly() {
        var accounts = List.of(
            new BusinessInvariant.AccountSnapshot("hot-0001", "HOT", 1000, 900),
            new BusinessInvariant.AccountSnapshot("acct-0001", "NORMAL", 500, 450));
        var orders = List.of(
            new BusinessInvariant.OrderSnapshot("r1", "hot-0001", 100),
            new BusinessInvariant.OrderSnapshot("r2", "acct-0001", 50));
        var results = BusinessInvariant.verifyAll(accounts, orders);
        assertThat(results).hasSize(2);
        assertThat(results.get("hot-0001").passed()).isTrue();
        assertThat(results.get("acct-0001").passed()).isTrue();
    }

    // ── Load parameters ──

    @Test void loadParametersRejectInvalid() {
        try {
            new BusinessFixtureCase.LoadParameters(-0.1, 10, 60, 20);
            assertThat(true).as("expected exception").isFalse();
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("hotTrafficRatio");
        }
    }

    @Test void poolConfigDefaults() {
        var pool = new BusinessFixtureCase.DbPoolConfig(6, 800);
        assertThat(pool.maxPoolSize()).isEqualTo(6);
    }

    // ── R2: Distribution tests ──

    @Test void hotTrafficDistributionMatchesExpectedRatio() {
        int total = 10000;
        var orders = FixtureSeed.generateOrders(
            BusinessFixtureCase.HOT_ACCOUNT_CONTENTION_V1, total);
        long hotCount = orders.stream()
            .filter(o -> o.accountId().equals("hot-0001")).count();
        double ratio = (double) hotCount / total;
        // Allow ±5% tolerance around 85%
        assertThat(ratio).isBetween(0.80, 0.90);
    }

    @Test void allNormalAccountsAreReachable() {
        // Generate many orders and verify all 99 normal accounts appear
        int total = 50000; // large enough to hit all 99
        var orders = FixtureSeed.generateOrders(
            BusinessFixtureCase.HOT_ACCOUNT_CONTENTION_V1, total);
        var normalIds = orders.stream()
            .map(BusinessInvariant.OrderSnapshot::accountId)
            .filter(id -> id.startsWith("acct-"))
            .collect(java.util.stream.Collectors.toSet());
        // With 15% × 50000 = 7500 orders over 99 accounts, all should appear
        assertThat(normalIds.size()).isEqualTo(99);
    }

    @Test void requestIdsAreUnique() {
        int total = 1000;
        var orders = FixtureSeed.generateOrders(
            BusinessFixtureCase.HOT_ACCOUNT_CONTENTION_V1, total);
        var ids = orders.stream()
            .map(BusinessInvariant.OrderSnapshot::requestId)
            .collect(java.util.stream.Collectors.toSet());
        assertThat(ids.size()).isEqualTo(total); // all unique
    }
}
