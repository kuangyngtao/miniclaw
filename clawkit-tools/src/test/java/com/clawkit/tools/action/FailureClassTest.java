package com.clawkit.tools.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FailureClassTest {

    @Test
    void everyFailureClassHasCertainty() {
        for (FailureClass fc : FailureClass.values()) {
            assertNotNull(fc.certainty(), fc + " must map to a certainty");
        }
    }

    @Test
    void unknownOutcomeClassesNeverAllowAutoRetry() {
        for (FailureClass fc : FailureClass.values()) {
            if (fc.certainty() == EffectCertainty.EFFECT_UNKNOWN
                || fc.certainty() == EffectCertainty.PARTIAL_EFFECT) {
                assertFalse(fc.certainty().allowsAutoRetry(),
                    fc + " must not allow auto retry");
            }
        }
    }

    @Test
    void decisionTableMatchesDesign() {
        // 参数错误、审批拒绝、预算不足 → NOT_DISPATCHED
        assertEquals(EffectCertainty.NOT_DISPATCHED, FailureClass.INVALID_ARGUMENTS.certainty());
        assertEquals(EffectCertainty.NOT_DISPATCHED, FailureClass.APPROVAL_REJECTED.certainty());
        assertEquals(EffectCertainty.NOT_DISPATCHED, FailureClass.BUDGET_EXHAUSTED.certainty());
        // 服务端执行前拒绝 → NO_EFFECT_CONFIRMED
        assertEquals(EffectCertainty.NO_EFFECT_CONFIRMED,
            FailureClass.SERVER_REJECTED_BEFORE_EXECUTION.certainty());
        // timeout / 断网 / 中断 → EFFECT_UNKNOWN
        assertEquals(EffectCertainty.EFFECT_UNKNOWN, FailureClass.TIMEOUT_OUTCOME_UNKNOWN.certainty());
        assertEquals(EffectCertainty.EFFECT_UNKNOWN, FailureClass.CONNECTION_LOST.certainty());
        assertEquals(EffectCertainty.EFFECT_UNKNOWN, FailureClass.INTERRUPTED_OUTCOME_UNKNOWN.certainty());
        // 已接受但无最终结果 → EFFECT_UNKNOWN
        assertEquals(EffectCertainty.EFFECT_UNKNOWN, FailureClass.ACCEPTED_NO_FINAL_RESULT.certainty());
        // 同幂等键返回既有结果 → EFFECT_CONFIRMED
        assertEquals(EffectCertainty.EFFECT_CONFIRMED, FailureClass.SERVER_DEDUP_REPLAY.certainty());
        // 明确部分执行 → PARTIAL_EFFECT
        assertEquals(EffectCertainty.PARTIAL_EFFECT, FailureClass.PARTIAL_EXECUTION.certainty());
        // 无法分类 → 保守 EFFECT_UNKNOWN
        assertEquals(EffectCertainty.EFFECT_UNKNOWN, FailureClass.UNCLASSIFIED.certainty());
    }

    @Test
    void autoRetryOnlyForSafeCertainties() {
        assertTrue(EffectCertainty.NOT_DISPATCHED.allowsAutoRetry());
        assertTrue(EffectCertainty.NO_EFFECT_CONFIRMED.allowsAutoRetry());
        assertFalse(EffectCertainty.EFFECT_CONFIRMED.allowsAutoRetry());
        assertFalse(EffectCertainty.PARTIAL_EFFECT.allowsAutoRetry());
        assertFalse(EffectCertainty.EFFECT_UNKNOWN.allowsAutoRetry());
    }
}
