package com.clawkit.reliability;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BudgetLedgerTest {

    @Test
    void reserveUpToGrantsAtMostRemaining() {
        BudgetLedger ledger = BudgetLedger.of(100);
        assertEquals(80, ledger.reserveUpTo(80));
        assertEquals(20, ledger.reserveUpTo(50)); // 只剩 20
        assertEquals(0, ledger.reserveUpTo(1));
        assertTrue(ledger.exhausted());
    }

    @Test
    void settleRefundsUnusedReservation() {
        BudgetLedger ledger = BudgetLedger.of(100);
        long reserved = ledger.reserveUpTo(80);
        ledger.settle(reserved, 30); // 实际只用 30
        assertEquals(70, ledger.remaining());
    }

    @Test
    void settleChargesOverconsumption() {
        BudgetLedger ledger = BudgetLedger.of(100);
        long reserved = ledger.reserveUpTo(50);
        ledger.settle(reserved, 120); // 实际用了 120 → 透支
        assertTrue(ledger.remaining() < 0);
        assertTrue(ledger.exhausted());
    }

    @Test
    void childCapCannotExceedParentRemaining() {
        BudgetLedger parent = BudgetLedger.of(100);
        parent.reserveUpTo(80);
        BudgetLedger child = parent.childCapped(500);
        assertEquals(20, child.remaining());
    }

    @Test
    void childReservationDrawsFromParent() {
        BudgetLedger parent = BudgetLedger.of(100);
        BudgetLedger child = parent.childCapped(60);
        assertEquals(60, child.reserveUpTo(100));
        assertEquals(40, parent.remaining());
        // 父耗尽后子无法继续（共享池空）
        assertEquals(40, parent.reserveUpTo(40));
        BudgetLedger child2 = parent.childCapped(60);
        assertEquals(0, child2.reserveUpTo(10));
    }

    @Test
    void childSettleRefundsBothLedgers() {
        BudgetLedger parent = BudgetLedger.of(100);
        BudgetLedger child = parent.childCapped(60);
        long reserved = child.reserveUpTo(60);
        child.settle(reserved, 10);
        assertEquals(50, child.remaining());
        assertEquals(90, parent.remaining());
    }

    @Test
    void concurrentReservationsNeverOverdraw() throws Exception {
        BudgetLedger ledger = BudgetLedger.of(10_000);
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong granted = new AtomicLong();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            workers.add(Thread.ofVirtual().start(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                for (int j = 0; j < 100; j++) {
                    granted.addAndGet(ledger.reserveUpTo(17));
                }
            }));
        }
        start.countDown();
        for (Thread t : workers) t.join(5000);
        // 总授予不超过初始额度，且与消耗后的余额一致
        assertTrue(granted.get() <= 10_000);
        assertEquals(10_000 - granted.get(), ledger.remaining());
    }
}
