package com.clawkit.reliability.gate;

import com.clawkit.reliability.attempt.ActionAttemptCoordinator;
import com.clawkit.reliability.attempt.AttemptState;
import com.clawkit.reliability.attempt.FileActionAttemptStore;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolExecutionStatus;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolOutputStats;
import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.ToolSideEffect;
import com.clawkit.tools.action.ActionDescriptor;
import com.clawkit.tools.action.ActionReliability;
import com.clawkit.tools.action.Digests;
import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.action.Reversibility;
import com.clawkit.tools.action.VerificationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** P1-G4 硬门禁：无描述符 fail-closed、durable intent、结果未知禁止重复写、确定性验证。 */
class SideEffectGateTest {

    @TempDir
    Path dir;

    private static final ToolMetadata META = ToolMetadata.of(
        "write", "", null, false, ToolRiskLevel.HIGH, false, false,
        Set.of(ToolSideEffect.FILE_WRITE));

    private SideEffectGate gate;
    private FileActionAttemptStore store;

    private SideEffectGate gate() {
        if (gate == null) {
            store = new FileActionAttemptStore(dir.resolve("rel"));
            gate = new SideEffectGate(new ActionAttemptCoordinator(store,
                new ActionAttemptCoordinator.AttemptPolicy(3, Duration.ZERO), null),
                attempt -> {
                    var verdict = DeterministicVerifier.verify(
                        attempt.descriptor().expectedEffects());
                    return new SideEffectGate.VerificationOutcome(
                        verdict.passed(), verdict.detail());
                });
        }
        return gate;
    }

    private static ActionDescriptor descriptor(String target, VerificationMode mode,
                                               List<String> expected) {
        return new ActionDescriptor("test.write", target, "d1",
            ToolRiskLevel.HIGH, Reversibility.COMPENSATABLE,
            ActionReliability.none(), mode, List.of(), expected, "", "");
    }

    private static ToolExecutionResult ok(String output) {
        return ToolExecutionResult.success("c1", "write", output, 5, META);
    }

    @Test
    void failsClosedWithoutDescriptor() {
        AtomicInteger bodyRuns = new AtomicInteger();
        var result = gate().execute(null, "c1", "write", META, null, "run-1",
            () -> { bodyRuns.incrementAndGet(); return ok("done"); });

        assertEquals(ToolExecutionStatus.BLOCKED, result.status());
        assertEquals(0, bodyRuns.get(), "无 ActionDescriptor 的副作用工具不得执行");
        assertTrue(result.output().contains("T-SEG-001"));
        assertEquals(EffectCertainty.NOT_DISPATCHED, result.effectCertainty());
    }

    @Test
    void dispatchIntentIsDurableBeforeBodyRuns() {
        var d = descriptor("file:/a", VerificationMode.MANUAL_REQUIRED, List.of());
        gate().execute(d, "c1", "write", META, null, "run-1", () -> {
            // body 执行时 DISPATCH_INTENT 必须已落盘
            var active = store.activeOnTarget("file:/a").orElseThrow();
            assertEquals(AttemptState.DISPATCH_INTENT, active.state());
            return ok("done");
        });
    }

    @Test
    void unknownOutcomeBlocksAutomaticRerunOfSameAction() {
        var d = descriptor("file:/a", VerificationMode.MANUAL_REQUIRED, List.of());
        AtomicInteger bodyRuns = new AtomicInteger();

        var first = gate().execute(d, "c1", "write", META, null, "run-1", () -> {
            bodyRuns.incrementAndGet();
            return ToolExecutionResult.timedOut("c1", "write", "no response", 30_000,
                ToolOutputStats.EMPTY, META);
        });
        assertEquals(EffectCertainty.EFFECT_UNKNOWN, first.effectCertainty());

        // 硬门禁：结果未知后自动重复写 = 0
        var second = gate().execute(d, "c2", "write", META, null, "run-1",
            () -> { bodyRuns.incrementAndGet(); return ok("again"); });
        assertEquals(ToolExecutionStatus.BLOCKED, second.status());
        assertTrue(second.output().contains("T-SEG-006") || second.output().contains("T-SEG-002"),
            "unknown outcome must block rerun: " + second.output());
        assertEquals(1, bodyRuns.get(), "同一动作在结果未知后不得再次执行");
    }

    @Test
    void deterministicVerificationPassEndsVerifiedSuccess() throws Exception {
        Path file = dir.resolve("out.txt");
        String content = "hello world";
        String hash = Digests.sha256Hex(content.getBytes(StandardCharsets.UTF_8));
        var d = descriptor("file:" + file, VerificationMode.DETERMINISTIC,
            List.of("file-sha256:" + file + ":" + hash));

        var result = gate().execute(d, "c1", "write", META, null, "run-1", () -> {
            try {
                Files.writeString(file, content, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return ok("written");
        });

        assertTrue(result.success());
        assertNotNull(result.attemptId());
        var attempt = store.byId(result.attemptId()).orElseThrow();
        assertEquals(AttemptState.VERIFIED_SUCCESS, attempt.state());
    }

    @Test
    void deterministicVerificationFailureFlagsResultAndPendsCompensation() throws Exception {
        Path file = dir.resolve("out.txt");
        var d = descriptor("file:" + file, VerificationMode.DETERMINISTIC,
            List.of("file-sha256:" + file + ":" + "0".repeat(64))); // 故意不匹配

        var result = gate().execute(d, "c1", "write", META, null, "run-1", () -> {
            try {
                Files.writeString(file, "actual content", StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return ok("written");
        });

        assertFalse(result.success());
        assertEquals("VERIFICATION_FAILED", result.errorCode());
        var attempt = store.byId(result.attemptId()).orElseThrow();
        assertEquals(AttemptState.COMPENSATION_PENDING, attempt.state());
    }

    @Test
    void manualRequiredNeverAutoVerified() {
        var d = descriptor("shell:/ws:cmd:abc", VerificationMode.MANUAL_REQUIRED, List.of());
        var result = gate().execute(d, "c1", "write", META, null, "run-1", () -> ok("exit=0"));

        assertFalse(result.success());
        assertEquals(ToolExecutionStatus.VERIFICATION_PENDING, result.status());
        var attempt = store.byId(result.attemptId()).orElseThrow();
        assertEquals(AttemptState.VERIFICATION_PENDING, attempt.state(),
            "MANUAL_REQUIRED 不得自动进入 VERIFIED_SUCCESS");
    }

    @Test
    void bodyExceptionReportsUnknownOutcome() {
        var d = descriptor("file:/a", VerificationMode.MANUAL_REQUIRED, List.of());
        var result = gate().execute(d, "c1", "write", META, null, "run-1",
            () -> { throw new IllegalStateException("boom mid-write"); });

        assertEquals(ToolExecutionStatus.INTERNAL_ERROR, result.status());
        assertEquals(EffectCertainty.EFFECT_UNKNOWN, result.effectCertainty());
        var attempt = store.byId(result.attemptId()).orElseThrow();
        assertEquals(AttemptState.OUTCOME_UNKNOWN, attempt.state());
    }

    @Test
    void confirmedNoEffectReleasesForRetry() {
        var d = descriptor("file:/a", VerificationMode.MANUAL_REQUIRED, List.of());
        AtomicInteger bodyRuns = new AtomicInteger();

        var first = gate().execute(d, "c1", "write", META, null, "run-1", () -> {
            bodyRuns.incrementAndGet();
            return ToolExecutionResult.error("c1", "write", "E_PRE", "pre-write failed", 1, META)
                .withReliability(EffectCertainty.NO_EFFECT_CONFIRMED,
                    com.clawkit.tools.action.FailureClass.LOCAL_ERROR_NO_EFFECT, null);
        });
        assertFalse(first.success());

        // 确认无副作用 → 允许有界重试
        var second = gate().execute(d, "c2", "write", META, null, "run-1",
            () -> { bodyRuns.incrementAndGet(); return ok("retry ok"); });
        assertEquals(ToolExecutionStatus.VERIFICATION_PENDING, second.status());
        assertEquals(2, bodyRuns.get());
    }

    @Test
    void stalePreconditionBlocksBeforeDispatch() throws Exception {
        Path file = dir.resolve("drift.txt");
        var d = new ActionDescriptor("test.write", "file:" + file, "d1",
            ToolRiskLevel.HIGH, Reversibility.COMPENSATABLE,
            ActionReliability.none(), VerificationMode.MANUAL_REQUIRED,
            List.of("pre-file-absent:" + file), List.of(), "", "");
        Files.writeString(file, "raced writer", StandardCharsets.UTF_8);
        AtomicInteger bodyRuns = new AtomicInteger();

        var result = gate().execute(d, "c1", "write", META, null, "run-1",
            () -> { bodyRuns.incrementAndGet(); return ok("should not run"); });

        assertEquals(ToolExecutionStatus.BLOCKED, result.status());
        assertTrue(result.output().contains("T-SEG-007"));
        assertEquals(0, bodyRuns.get());
        assertEquals(AttemptState.FAILED_NO_EFFECT,
            store.byId(result.attemptId()).orElseThrow().state());
    }

    @Test
    void deterministicModeWithoutAssertionsFailsClosed() {
        var d = descriptor("file:/empty", VerificationMode.DETERMINISTIC, List.of());
        var result = gate().execute(d, "c1", "write", META, null, "run-1",
            () -> ok("reported write"));

        assertEquals("VERIFICATION_FAILED", result.errorCode());
        assertEquals(AttemptState.COMPENSATION_PENDING,
            store.byId(result.attemptId()).orElseThrow().state());
    }

    @Test
    void journalFailureAfterDispatchOverridesToolSuccess() throws Exception {
        var d = descriptor("file:/journal-fault", VerificationMode.MANUAL_REQUIRED, List.of());
        var result = gate().execute(d, "c1", "write", META, null, "run-1", () -> {
            try {
                String json = "{}";
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(json.getBytes(StandardCharsets.UTF_8));
                Files.writeString(dir.resolve("rel").resolve("journal.jsonl"),
                    "invalid-middle-line\n" + Long.toHexString(crc.getValue()) + " " + json + "\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return ok("remote committed");
        });

        assertEquals(ToolExecutionStatus.INTERNAL_ERROR, result.status());
        assertEquals("RELIABILITY_JOURNAL_FAILURE", result.errorCode());
        assertEquals(EffectCertainty.EFFECT_UNKNOWN, result.effectCertainty());
    }
}
