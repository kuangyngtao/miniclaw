package com.clawkit.reliability;

import com.clawkit.tools.control.CancelRegistration;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.control.ExecutionHaltedException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CancellationTreeTest {

    @Test
    void cancelCascadesToAllDescendants() {
        CancellationTree root = CancellationTree.unbounded();
        CancellationTree child = root.child(null, null);
        CancellationTree grandChild = child.child(null, null);

        root.cancel();

        assertTrue(root.isCancelled());
        assertTrue(child.isCancelled());
        assertTrue(grandChild.isCancelled());
    }

    @Test
    void childCancelDoesNotAffectParent() {
        CancellationTree root = CancellationTree.unbounded();
        CancellationTree child = root.child(null, null);
        child.cancel();
        assertTrue(child.isCancelled());
        assertFalse(root.isCancelled());
    }

    @Test
    void listenersFireExactlyOnce() {
        CancellationTree root = CancellationTree.unbounded();
        AtomicInteger fired = new AtomicInteger();
        root.onCancel(fired::incrementAndGet);
        root.cancel();
        root.cancel(); // 第二次取消不重复触发
        assertEquals(1, fired.get());
    }

    @Test
    void listenerRegisteredAfterCancelFiresImmediately() {
        CancellationTree root = CancellationTree.unbounded();
        root.cancel();
        AtomicInteger fired = new AtomicInteger();
        root.onCancel(fired::incrementAndGet);
        assertEquals(1, fired.get());
    }

    @Test
    void closedRegistrationDoesNotFire() {
        CancellationTree root = CancellationTree.unbounded();
        AtomicInteger fired = new AtomicInteger();
        CancelRegistration reg = root.onCancel(fired::incrementAndGet);
        reg.close();
        root.cancel();
        assertEquals(0, fired.get());
    }

    @Test
    void childCreatedAfterCancelIsBornCancelled() {
        CancellationTree root = CancellationTree.unbounded();
        root.cancel();
        CancellationTree child = root.child(null, null);
        assertTrue(child.isCancelled());
        assertThrows(ExecutionHaltedException.class, child::checkpoint);
    }

    @Test
    void childDeadlineIsMinOfParentAndOwn() {
        Instant parentDeadline = Instant.now().plus(Duration.ofMinutes(1));
        CancellationTree root = CancellationTree.root(parentDeadline, null);
        // 子节点要求 10 分钟 → 仍受父 1 分钟约束
        CancellationTree longChild = root.child(Duration.ofMinutes(10), null);
        assertEquals(parentDeadline, longChild.deadline().orElseThrow());
        // 子节点要求 1 秒 → 更早
        CancellationTree shortChild = root.child(Duration.ofSeconds(1), null);
        assertTrue(shortChild.deadline().orElseThrow().isBefore(parentDeadline));
    }

    @Test
    void childSharesParentBudgetLedger() {
        BudgetLedger ledger = BudgetLedger.of(100);
        CancellationTree root = CancellationTree.root(null, ledger);
        CancellationTree child = root.child(null, null);
        assertSame(ledger, child.tokenBudget());
        // 子消耗直接反映在父账本
        assertEquals(60, child.tokenBudget().reserveUpTo(60));
        assertEquals(40, root.tokenBudget().remaining());
    }

    @Test
    void childCappedBudgetDrawsFromSharedPool() {
        BudgetLedger ledger = BudgetLedger.of(100);
        CancellationTree root = CancellationTree.root(null, ledger);
        CancellationTree child = root.child(null, 30L);
        // 子只能拿 30
        assertEquals(30, child.tokenBudget().reserveUpTo(1000));
        assertEquals(0, child.tokenBudget().reserveUpTo(1));
        // 且从共享池扣除
        assertEquals(70, ledger.remaining());
    }

    @Test
    void childOfForeignControlInheritsCancelAndBudget() {
        // 非 CancellationTree 的 ExecutionControl 通过 onCancel 回调级联
        var cancelled = new AtomicInteger();
        var foreign = new ExecutionControl() {
            boolean c;
            Runnable listener;
            @Override public boolean isCancelled() { return c; }
            @Override public Optional<Instant> deadline() { return Optional.empty(); }
            @Override public com.clawkit.tools.control.TokenBudget tokenBudget() {
                return com.clawkit.tools.control.TokenBudget.unlimited();
            }
            @Override public CancelRegistration onCancel(Runnable action) {
                listener = action;
                return CancelRegistration.noop();
            }
            void cancel() { c = true; if (listener != null) listener.run(); }
        };
        CancellationTree child = CancellationTree.childOf(foreign);
        child.onCancel(cancelled::incrementAndGet);
        assertFalse(child.isCancelled());
        foreign.cancel();
        assertTrue(child.isCancelled());
        assertEquals(1, cancelled.get());
    }

    @Test
    void checkpointReasonsMapCorrectly() {
        CancellationTree cancelledTree = CancellationTree.unbounded();
        cancelledTree.cancel();
        assertEquals(ExecutionHaltedException.Reason.CANCELLED,
            assertThrows(ExecutionHaltedException.class, cancelledTree::checkpoint).reason());

        CancellationTree expired = CancellationTree.root(Instant.now().minusSeconds(1), null);
        assertEquals(ExecutionHaltedException.Reason.DEADLINE_EXCEEDED,
            assertThrows(ExecutionHaltedException.class, expired::checkpoint).reason());

        CancellationTree broke = CancellationTree.root(null, BudgetLedger.of(0));
        assertEquals(ExecutionHaltedException.Reason.BUDGET_EXHAUSTED,
            assertThrows(ExecutionHaltedException.class, broke::checkpoint).reason());
    }
}
