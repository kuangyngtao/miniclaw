package com.clawkit.reliability;

import com.clawkit.tools.ToolError;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolMetadataProvenance;
import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.action.FailureClass;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** P1-A2：DefaultToolRetryPolicy 八重门禁和 full jitter 测试。 */
class DefaultToolRetryPolicyTest {

    private final Random seeded = new Random(42);
    private final DefaultToolRetryPolicy policy = new DefaultToolRetryPolicy(seeded, 3);

    private static ToolMetadata readOnlyTrusted(String name) {
        return new ToolMetadata(name, "", null, null,
            new com.clawkit.tools.ToolBehavior(
                true, com.clawkit.tools.ToolRiskLevel.LOW,
                false, true, false, false, java.util.Set.of()),
            com.clawkit.tools.ToolExecutionPolicy.readOnlyDefaults(),
            ToolMetadataProvenance.builtin(name));
    }

    private static ToolMetadata writeTool(String name) {
        return ToolMetadata.of(name, "", null,
            false, com.clawkit.tools.ToolRiskLevel.HIGH,
            true, true, java.util.Set.of(com.clawkit.tools.ToolSideEffect.FILE_WRITE));
    }

    private static ToolMetadata untrustedReadOnly(String name) {
        return new ToolMetadata(name, "", null, null,
            new com.clawkit.tools.ToolBehavior(
                true, com.clawkit.tools.ToolRiskLevel.LOW,
                false, true, false, false, java.util.Set.of()),
            com.clawkit.tools.ToolExecutionPolicy.readOnlyDefaults(),
            ToolMetadataProvenance.conservativeDefault());
    }

    private ToolRetryContext ctx(ToolExecutionResult r, ToolMetadata m, int attempts) {
        return new ToolRetryContext(r, m, attempts, 3,
            Duration.ofMillis(100), Duration.ofSeconds(30));
    }

    private ToolExecutionResult retryableReadFailure(String name) {
        var meta = readOnlyTrusted(name);
        return new ToolExecutionResult(
            "id", name, "read error", true, "IO_ERROR", 10, 0, false, false, null, meta,
            com.clawkit.tools.ToolExecutionStatus.TOOL_ERROR,
            ToolError.retryable("IO_ERROR", "transient read error"),
            com.clawkit.tools.ToolOutputStats.EMPTY, null, "audit",
            EffectCertainty.NO_EFFECT_CONFIRMED,
            FailureClass.LOCAL_ERROR_NO_EFFECT, null, "att-1");
    }

    // ── positive cases ───────────────────────────────────────────────

    @Test
    void allowsRetryOnTransientReadFailure() {
        var r = retryableReadFailure("grep");
        var d = policy.decide(ctx(r, r.metadata(), 1));
        assertTrue(d.retrySameInput());
        assertEquals(ToolRetryDecision.TRANSIENT_READ_FAILURE, d.reasonCode());
    }

    @Test
    void jitterWithinBounds() {
        var r = retryableReadFailure("grep");
        // attempt 1: cap = min(200*2^0, 2000) = 200ms
        var d1 = policy.decide(ctx(r, r.metadata(), 1));
        assertTrue(d1.retrySameInput());
        assertTrue(d1.delay().toMillis() <= 200);

        // attempt 2: cap = min(200*2^1, 2000) = 400ms
        var d2 = policy.decide(ctx(r, r.metadata(), 2));
        assertTrue(d2.retrySameInput());
        assertTrue(d2.delay().toMillis() <= 400);
    }

    @Test
    void jitterCapCappedAt2000ms() {
        var r = retryableReadFailure("grep");
        var d = policy.decide(ctx(r, r.metadata(), 10));
        assertTrue(d.delay().toMillis() <= 2000);
    }

    // ── gate: NOT_READ_ONLY ──────────────────────────────────────────

    @Test
    void rejectsWriteTool() {
        var meta = writeTool("bash");
        var r = new ToolExecutionResult(
            "id", "bash", "error", true, "IO_ERROR", 10, 0, false, false, null, meta,
            com.clawkit.tools.ToolExecutionStatus.TOOL_ERROR,
            ToolError.retryable("IO_ERROR", "error"),
            com.clawkit.tools.ToolOutputStats.EMPTY, null, "audit",
            EffectCertainty.NO_EFFECT_CONFIRMED,
            FailureClass.LOCAL_ERROR_NO_EFFECT, null, "att-1");
        var d = policy.decide(ctx(r, meta, 1));
        assertFalse(d.retrySameInput());
        assertEquals(ToolRetryDecision.NOT_READ_ONLY, d.reasonCode());
    }

    // ── gate: UNTRUSTED_METADATA ─────────────────────────────────────

    @Test
    void rejectsUntrustedMCP() {
        var meta = untrustedReadOnly("mcp_tool");
        var r = retryableReadFailure("mcp_tool");
        var d = policy.decide(ctx(r, meta, 1));
        assertFalse(d.retrySameInput());
        assertEquals(ToolRetryDecision.UNTRUSTED_METADATA, d.reasonCode());
    }

    @Test
    void rejectsEvenIfUntrustedMCPClaimsReadOnlyAndRetryable() {
        var meta = untrustedReadOnly("mcp_tool");
        var r = retryableReadFailure("mcp_tool");
        var d = policy.decide(ctx(r, meta, 1));
        assertFalse(d.retrySameInput());
    }

    // ── gate: NOT_RETRYABLE (error not retryable) ─────────────────────

    @Test
    void rejectsNonRetryableError() {
        var meta = readOnlyTrusted("grep");
        var r = new ToolExecutionResult(
            "id", "grep", "invalid regex", true, "INVALID_REGEX", 10, 0, false, false, null, meta,
            com.clawkit.tools.ToolExecutionStatus.TOOL_ERROR,
            ToolError.fatal("INVALID_REGEX", "bad pattern"),
            com.clawkit.tools.ToolOutputStats.EMPTY, null, "audit",
            EffectCertainty.NO_EFFECT_CONFIRMED,
            FailureClass.LOCAL_ERROR_NO_EFFECT, null, "att-1");
        var d = policy.decide(ctx(r, meta, 1));
        assertFalse(d.retrySameInput());
        assertEquals(ToolRetryDecision.NOT_RETRYABLE, d.reasonCode());
    }

    // ── gate: INVALID_ARGUMENTS → REPAIR_INPUT (not RETRY_ALLOWED) ──

    @Test
    void rejectsInvalidArguments() {
        var meta = readOnlyTrusted("grep");
        var r = new ToolExecutionResult(
            "id", "grep", "bad args", true, "INVALID_ARGUMENTS", 10, 0, false, false, null, meta,
            com.clawkit.tools.ToolExecutionStatus.INVALID_ARGUMENTS,
            ToolError.retryable("INVALID_ARGUMENTS", "bad args"),
            com.clawkit.tools.ToolOutputStats.EMPTY, null, "audit",
            EffectCertainty.NO_EFFECT_CONFIRMED,
            FailureClass.INVALID_ARGUMENTS, null, "att-1");
        var d = policy.decide(ctx(r, meta, 1));
        assertFalse(d.retrySameInput(),
            "INVALID_ARGUMENTS maps to REPAIR_INPUT, not RETRY_ALLOWED");
    }

    // ── gate: ATTEMPT_LIMIT ──────────────────────────────────────────

    @Test
    void stopsAtAttemptLimit() {
        var r = retryableReadFailure("grep");
        var d = policy.decide(ctx(r, r.metadata(), 3));
        assertFalse(d.retrySameInput());
        assertEquals(ToolRetryDecision.ATTEMPT_LIMIT, d.reasonCode());
    }

    // ── gate: DEADLINE_TOO_CLOSE ─────────────────────────────────────

    @Test
    void stopsWhenDeadlineExceeded() {
        var r = retryableReadFailure("grep");
        var c = new ToolRetryContext(r, r.metadata(), 1, 3,
            Duration.ofMillis(100), Duration.ofMillis(-1));
        var d = policy.decide(c);
        assertFalse(d.retrySameInput());
        assertEquals(ToolRetryDecision.DEADLINE_TOO_CLOSE, d.reasonCode());
    }

    // ── side effects gate ────────────────────────────────────────────

    @Test
    void rejectsToolWithSideEffects() {
        var meta = new ToolMetadata("write", "", null, null,
            new com.clawkit.tools.ToolBehavior(
                true, com.clawkit.tools.ToolRiskLevel.LOW,
                false, true, false, false,
                java.util.Set.of(com.clawkit.tools.ToolSideEffect.FILE_WRITE)),
            com.clawkit.tools.ToolExecutionPolicy.readOnlyDefaults(),
            ToolMetadataProvenance.builtin("write"));
        var r = retryableReadFailure("write");
        var d = policy.decide(ctx(r, meta, 1));
        assertFalse(d.retrySameInput());
        assertEquals(ToolRetryDecision.ACTION_NOT_RETRY, d.reasonCode());
    }

    // ── EFFECT_UNKNOWN gate ──────────────────────────────────────────

    @Test
    void rejectsEffectUnknownEvenIfRetryable() {
        var meta = readOnlyTrusted("grep");
        var r = new ToolExecutionResult(
            "id", "grep", "timeout", true, "TIMED_OUT", 30000, 0, false, true, null, meta,
            com.clawkit.tools.ToolExecutionStatus.TIMED_OUT,
            ToolError.retryable("TIMED_OUT", "timeout"),
            com.clawkit.tools.ToolOutputStats.EMPTY, null, "audit",
            EffectCertainty.EFFECT_UNKNOWN,
            FailureClass.TIMEOUT_OUTCOME_UNKNOWN, null, "att-1");
        var d = policy.decide(ctx(r, meta, 1));
        assertFalse(d.retrySameInput());
    }
}
