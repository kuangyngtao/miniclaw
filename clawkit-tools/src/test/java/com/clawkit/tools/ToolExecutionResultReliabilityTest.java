package com.clawkit.tools;

import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.action.FailureClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** P1-G0：ToolExecutionResult 可靠性维度派生与 OutputEnvelope 不变量。 */
class ToolExecutionResultReliabilityTest {

    private static final ToolMetadata WRITE_META = ToolMetadata.of(
        "write", "", null, false, ToolRiskLevel.HIGH, false, true,
        Set.of(ToolSideEffect.FILE_WRITE));

    private static final ToolMetadata READ_META = ToolMetadata.of(
        "read", "", null, true, ToolRiskLevel.LOW, false, false, Set.of());

    @Test
    void successOnSideEffectToolIsEffectConfirmed() {
        var r = ToolExecutionResult.success("id", "write", "ok", 5, WRITE_META);
        assertEquals(FailureClass.NONE, r.failureClass());
        assertEquals(EffectCertainty.EFFECT_CONFIRMED, r.effectCertainty());
    }

    @Test
    void readOnlyToolAlwaysNoEffectConfirmed() {
        var ok = ToolExecutionResult.success("id", "read", "ok", 5, READ_META);
        var err = ToolExecutionResult.error("id", "read", "E", "boom", 5, READ_META);
        assertEquals(EffectCertainty.NO_EFFECT_CONFIRMED, ok.effectCertainty());
        assertEquals(EffectCertainty.NO_EFFECT_CONFIRMED, err.effectCertainty());
    }

    @Test
    void timeoutOnSideEffectToolIsEffectUnknown() {
        var r = ToolExecutionResult.timedOut("id", "bash", "partial", 30000,
            ToolOutputStats.EMPTY, WRITE_META);
        assertEquals(FailureClass.TIMEOUT_OUTCOME_UNKNOWN, r.failureClass());
        assertEquals(EffectCertainty.EFFECT_UNKNOWN, r.effectCertainty());
        assertFalse(r.effectCertainty().allowsAutoRetry());
    }

    @Test
    void toolErrorOnSideEffectToolIsConservativelyUnknown() {
        var r = ToolExecutionResult.error("id", "write", "E_IO", "disk error", 5, WRITE_META);
        assertEquals(FailureClass.EXECUTION_ERROR_OUTCOME_UNKNOWN, r.failureClass());
        assertEquals(EffectCertainty.EFFECT_UNKNOWN, r.effectCertainty());
    }

    @Test
    void rejectedAndBlockedAreNotDispatched() {
        var rejected = ToolExecutionResult.of("id", "write", "no",
            ToolExecutionStatus.REJECTED, ToolError.fatal("REJECTED", "no"), 1,
            ToolOutputStats.EMPTY, null, WRITE_META, null);
        var blocked = ToolExecutionResult.blocked("id", "write", "PLAN_BLOCKED", 1, WRITE_META);
        assertEquals(EffectCertainty.NOT_DISPATCHED, rejected.effectCertainty());
        assertEquals(FailureClass.APPROVAL_REJECTED, rejected.failureClass());
        assertEquals(EffectCertainty.NOT_DISPATCHED, blocked.effectCertainty());
        assertEquals(FailureClass.PERMISSION_BLOCKED, blocked.failureClass());
    }

    @Test
    void cancelledFactoryDistinguishesDispatchBoundary() {
        var before = ToolExecutionResult.cancelled("id", "write", "cancelled", 1, WRITE_META, true);
        var during = ToolExecutionResult.cancelled("id", "write", "cancelled", 1, WRITE_META, false);
        assertEquals(ToolExecutionStatus.CANCELLED, before.status());
        assertEquals(EffectCertainty.NOT_DISPATCHED, before.effectCertainty());
        assertEquals(FailureClass.CANCELLED_BEFORE_DISPATCH, before.failureClass());
        assertEquals(EffectCertainty.EFFECT_UNKNOWN, during.effectCertainty());
        assertEquals(FailureClass.INTERRUPTED_OUTCOME_UNKNOWN, during.failureClass());
    }

    @Test
    void deprecatedConstructorStillDerivesReliabilityFields() {
        @SuppressWarnings("deprecation")
        var r = new ToolExecutionResult("id", "write", "boom", true, "TOOL_ERROR",
            5, 4, false, false, null, WRITE_META);
        assertNotNull(r.failureClass());
        assertNotNull(r.effectCertainty());
        assertEquals(EffectCertainty.EFFECT_UNKNOWN, r.effectCertainty());
    }

    @Test
    void withReliabilityOverridesDerivedValues() {
        var r = ToolExecutionResult.error("id", "write", "E_IO", "pre-write check failed", 5, WRITE_META)
            .withReliability(EffectCertainty.NO_EFFECT_CONFIRMED,
                FailureClass.LOCAL_ERROR_NO_EFFECT, "attempt-1");
        assertEquals(EffectCertainty.NO_EFFECT_CONFIRMED, r.effectCertainty());
        assertEquals(FailureClass.LOCAL_ERROR_NO_EFFECT, r.failureClass());
        assertEquals("attempt-1", r.attemptId());
    }

    @Test
    void outputEnvelopeInvariants() {
        var env = new OutputEnvelope("head", "tail", List.of("ERROR x"),
            100, 40, 60, "max-bytes", "ab12", List.of("evidence:1"), false, "UTF-8");
        assertTrue(env.truncated());
        assertEquals(60, env.omittedBytes());
        assertThrows(IllegalArgumentException.class, () -> new OutputEnvelope(
            "h", "t", List.of(), 100, 50, 40, null, null, List.of(), false, null));

        var complete = OutputEnvelope.complete("hello");
        assertFalse(complete.truncated());
        assertEquals(5, complete.totalBytes());
        assertEquals(64, complete.sha256().length());

        var r = ToolExecutionResult.success("id", "bash", "hello", 1, WRITE_META)
            .withOutputEnvelope(complete);
        assertEquals(complete, r.outputEnvelope());
    }
}
