package com.clawkit.reliability;

import com.clawkit.tools.action.ActionReliability;
import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.action.FailureClass;
import com.clawkit.tools.action.RecoveryDirective;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FailureDecisionTableTest {

    @Test
    void everyFailureClassHasDirective() {
        for (FailureClass fc : FailureClass.values()) {
            assertNotNull(FailureDecisionTable.directiveFor(fc));
        }
    }

    @Test
    void retryAllowedOnlyForConfirmedNoEffect() {
        for (FailureClass fc : FailureClass.values()) {
            if (FailureDecisionTable.directiveFor(fc) == RecoveryDirective.RETRY_ALLOWED) {
                assertTrue(fc.certainty().allowsAutoRetry(),
                    fc + " maps to RETRY_ALLOWED but certainty is " + fc.certainty());
            }
        }
    }

    @Test
    void unknownOutcomeNeverAutoRetriesWithoutDedup() {
        assertFalse(FailureDecisionTable.autoRetryAllowed(
            FailureClass.TIMEOUT_OUTCOME_UNKNOWN, ActionReliability.none()));
        assertFalse(FailureDecisionTable.autoRetryAllowed(
            FailureClass.CONNECTION_LOST, ActionReliability.none()));
        assertFalse(FailureDecisionTable.autoRetryAllowed(
            FailureClass.INTERRUPTED_OUTCOME_UNKNOWN, null));
    }

    @Test
    void serverDedupEnablesRetryOnUnknown() {
        var dedup = new ActionReliability(false, true, false);
        assertTrue(FailureDecisionTable.autoRetryAllowed(
            FailureClass.TIMEOUT_OUTCOME_UNKNOWN, dedup));
    }

    @Test
    void idempotentSetterWithReconcileEnablesRetryOnUnknown() {
        assertTrue(FailureDecisionTable.autoRetryAllowed(
            FailureClass.TIMEOUT_OUTCOME_UNKNOWN, ActionReliability.idempotentSetter()));
        // 只有幂等但不可 reconcile → 不允许
        var noReconcile = new ActionReliability(true, false, false);
        assertFalse(FailureDecisionTable.autoRetryAllowed(
            FailureClass.TIMEOUT_OUTCOME_UNKNOWN, noReconcile));
    }

    @Test
    void partialEffectNeverAutoRetries() {
        assertEquals(EffectCertainty.PARTIAL_EFFECT, FailureClass.PARTIAL_EXECUTION.certainty());
        assertFalse(FailureDecisionTable.autoRetryAllowed(
            FailureClass.PARTIAL_EXECUTION, ActionReliability.idempotentSetter()));
        assertEquals(RecoveryDirective.VERIFY,
            FailureDecisionTable.directiveFor(FailureClass.PARTIAL_EXECUTION));
    }

    @Test
    void invalidArgumentsRequiresRepairNotRetry() {
        assertEquals(RecoveryDirective.REPAIR_INPUT,
            FailureDecisionTable.directiveFor(FailureClass.INVALID_ARGUMENTS));
        assertFalse(FailureDecisionTable.autoRetryAllowed(
            FailureClass.INVALID_ARGUMENTS, ActionReliability.none()),
            "INVALID_ARGUMENTS must not allow same-input auto-retry");
    }

    @Test
    void deadlineExceededBeforeDispatchAborts() {
        assertEquals(RecoveryDirective.ABORT,
            FailureDecisionTable.directiveFor(FailureClass.DEADLINE_EXCEEDED_BEFORE_DISPATCH));
        assertEquals(EffectCertainty.NOT_DISPATCHED,
            FailureClass.DEADLINE_EXCEEDED_BEFORE_DISPATCH.certainty());
        assertFalse(FailureDecisionTable.autoRetryAllowed(
            FailureClass.DEADLINE_EXCEEDED_BEFORE_DISPATCH, null),
            "DEADLINE_EXCEEDED_BEFORE_DISPATCH must not auto-retry");
    }

    @Test
    void terminalDecisionsMatchDesign() {
        assertEquals(RecoveryDirective.ABORT,
            FailureDecisionTable.directiveFor(FailureClass.BUDGET_EXHAUSTED));
        assertEquals(RecoveryDirective.ABORT,
            FailureDecisionTable.directiveFor(FailureClass.CANCELLED_BEFORE_DISPATCH));
        assertEquals(RecoveryDirective.USER_INPUT,
            FailureDecisionTable.directiveFor(FailureClass.APPROVAL_REJECTED));
        assertEquals(RecoveryDirective.VERIFY,
            FailureDecisionTable.directiveFor(FailureClass.ACCEPTED_NO_FINAL_RESULT));
        assertEquals(RecoveryDirective.VERIFY,
            FailureDecisionTable.directiveFor(FailureClass.SERVER_DEDUP_REPLAY));
        assertEquals(RecoveryDirective.RECOLLECT,
            FailureDecisionTable.directiveFor(FailureClass.TIMEOUT_OUTCOME_UNKNOWN));
    }
}
