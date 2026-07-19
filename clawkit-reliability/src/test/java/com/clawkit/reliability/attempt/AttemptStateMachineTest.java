package com.clawkit.reliability.attempt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttemptStateMachineTest {

    @Test
    void terminalStatesAcceptNoTransitions() {
        for (AttemptState terminal : new AttemptState[]{
                AttemptState.VERIFIED_SUCCESS, AttemptState.CANCELLED_NO_EFFECT,
                AttemptState.FAILED_NO_EFFECT, AttemptState.COMPENSATED, AttemptState.ESCALATED}) {
            assertTrue(terminal.isTerminal());
            for (AttemptState to : AttemptState.values()) {
                assertFalse(terminal.canTransitionTo(to),
                    terminal + " must not transition to " + to);
            }
        }
    }

    @Test
    void dispatchIntentNeverGoesBackToReady() {
        assertFalse(AttemptState.DISPATCH_INTENT.canTransitionTo(AttemptState.READY));
        assertFalse(AttemptState.DISPATCH_INTENT.canTransitionTo(AttemptState.CANCELLED_NO_EFFECT),
            "intent 落盘后取消不等于未执行，不允许 CANCELLED_NO_EFFECT");
    }

    @Test
    void unknownOutcomeIsStickyUntilReconcileOrHuman() {
        assertFalse(AttemptState.OUTCOME_UNKNOWN.releasesTarget());
        assertTrue(AttemptState.OUTCOME_UNKNOWN.canTransitionTo(AttemptState.RECONCILING));
        assertTrue(AttemptState.OUTCOME_UNKNOWN.canTransitionTo(AttemptState.ESCALATED));
        assertFalse(AttemptState.OUTCOME_UNKNOWN.canTransitionTo(AttemptState.VERIFIED_SUCCESS));
        assertFalse(AttemptState.OUTCOME_UNKNOWN.canTransitionTo(AttemptState.FAILED_NO_EFFECT),
            "未知结果不能不经 reconcile 直接宣称无副作用");
    }

    @Test
    void verifiedSuccessOnlyReachableThroughVerifying() {
        for (AttemptState from : AttemptState.values()) {
            if (from.canTransitionTo(AttemptState.VERIFIED_SUCCESS)) {
                assertEquals(AttemptState.VERIFYING, from,
                    "VERIFIED_SUCCESS 只能来自 VERIFYING，实际来自 " + from);
            }
        }
    }

    @Test
    void lockHoldingStatesMatchDesign() {
        // 持锁 = 派发不确定窗口 + 效果错误窗口
        assertFalse(AttemptState.DISPATCH_INTENT.releasesTarget());
        assertFalse(AttemptState.EXECUTION_REPORTED.releasesTarget());
        assertFalse(AttemptState.OUTCOME_UNKNOWN.releasesTarget());
        assertFalse(AttemptState.RECONCILING.releasesTarget());
        assertFalse(AttemptState.COMPENSATION_PENDING.releasesTarget());
        // 验证尚未收敛时继续持锁，避免后续写污染新鲜证据。
        assertFalse(AttemptState.VERIFICATION_PENDING.releasesTarget());
        assertFalse(AttemptState.VERIFYING.releasesTarget());
        assertTrue(AttemptState.FAILED_NO_EFFECT.releasesTarget());
        assertTrue(AttemptState.VERIFIED_SUCCESS.releasesTarget());
        assertTrue(AttemptState.CANCELLED_NO_EFFECT.releasesTarget());
    }
}
