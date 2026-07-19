package com.clawkit.reliability.gate;

import com.clawkit.reliability.attempt.ActionAttempt;
import com.clawkit.reliability.attempt.AttemptState;
import com.clawkit.reliability.attempt.FileActionAttemptStore;
import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.action.ActionDescriptor;
import com.clawkit.tools.action.ActionReliability;
import com.clawkit.tools.action.Digests;
import com.clawkit.tools.action.Reversibility;
import com.clawkit.tools.action.VerificationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P1-G5：崩溃窗口恢复与确定性 reconcile。 */
class RecoveryScannerTest {

    @TempDir
    Path dir;

    private FileActionAttemptStore store() {
        return new FileActionAttemptStore(dir.resolve("rel"));
    }

    private static ActionDescriptor fileWrite(Path file, String expectedContent, String preContent) {
        String expectedHash = Digests.sha256Hex(expectedContent.getBytes(StandardCharsets.UTF_8));
        List<String> pre = preContent == null
            ? List.of("pre-file-absent:" + file)
            : List.of("pre-file-sha256:" + file + ":"
                + Digests.sha256Hex(preContent.getBytes(StandardCharsets.UTF_8)));
        return new ActionDescriptor("file.write", "file:" + file, "d1",
            ToolRiskLevel.HIGH, Reversibility.COMPENSATABLE,
            ActionReliability.idempotentSetter(), VerificationMode.DETERMINISTIC,
            pre, List.of("file-sha256:" + file + ":" + expectedHash), "", "");
    }

    private static ActionDescriptor bashLike() {
        return new ActionDescriptor("bash.exec", "shell:/ws:cmd:abc", "d1",
            ToolRiskLevel.HIGH, Reversibility.IRREVERSIBLE,
            ActionReliability.none(), VerificationMode.MANUAL_REQUIRED,
            List.of(), List.of(), "", "");
    }

    private ActionAttempt driveTo(FileActionAttemptStore s, ActionDescriptor d,
                                  String actionId, AttemptState target) {
        var a = s.create(d, actionId, 1, "run-1", null, "ACTION");
        var order = List.of(AttemptState.PRECHECKING, AttemptState.READY,
            AttemptState.DISPATCH_INTENT, AttemptState.EXECUTION_REPORTED);
        for (AttemptState next : order) {
            if (a.state() == target) break;
            a = s.transition(a.attemptId(), a.version(), next, "drive");
            if (a.state() == target) break;
        }
        assertEquals(target, a.state(), "test setup must reach " + target);
        return a;
    }

    @Test
    void preDispatchStatesAreCancelledNoEffect() {
        try (var s = store()) {
            var a = s.create(fileWrite(dir.resolve("f.txt"), "x", null), "act-1", 1, "run-1", null, "ACTION");
            var report = RecoveryScanner.scan(s);
            assertEquals(1, report.cancelledNoEffect());
            assertEquals(AttemptState.CANCELLED_NO_EFFECT,
                s.byId(a.attemptId()).orElseThrow().state());
        }
    }

    @Test
    void dispatchIntentWithEffectPresentIsQueuedForIndependentVerification() throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "new content", StandardCharsets.UTF_8); // 效果已发生
        try (var s = store()) {
            var a = driveTo(s, fileWrite(file, "new content", "old content"),
                "act-1", AttemptState.DISPATCH_INTENT);
            var report = RecoveryScanner.scan(s);
            assertEquals(1, report.movedToUnknown());
            assertEquals(1, report.reconciledEffectPresent());
            assertEquals(1, report.verificationPending());
            assertEquals(AttemptState.VERIFICATION_PENDING,
                s.byId(a.attemptId()).orElseThrow().state());
            assertTrue(s.activeOnTarget("file:" + file).isPresent());
        }
    }

    @Test
    void dispatchIntentWithPreStateIntactIsConfirmedNoEffect() throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "old content", StandardCharsets.UTF_8); // 未生效
        try (var s = store()) {
            var a = driveTo(s, fileWrite(file, "new content", "old content"),
                "act-1", AttemptState.DISPATCH_INTENT);
            var report = RecoveryScanner.scan(s);
            assertEquals(1, report.confirmedNoEffect());
            assertEquals(AttemptState.FAILED_NO_EFFECT,
                s.byId(a.attemptId()).orElseThrow().state());
        }
    }

    @Test
    void dispatchIntentWithDriftedStateEscalates() throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "someone else changed this", StandardCharsets.UTF_8);
        try (var s = store()) {
            var a = driveTo(s, fileWrite(file, "new content", "old content"),
                "act-1", AttemptState.DISPATCH_INTENT);
            var report = RecoveryScanner.scan(s);
            assertEquals(1, report.escalated());
            assertEquals(AttemptState.ESCALATED,
                s.byId(a.attemptId()).orElseThrow().state());
        }
    }

    @Test
    void noReconcileCapabilityEscalatesInsteadOfGuessing() {
        try (var s = store()) {
            var a = driveTo(s, bashLike(), "act-1", AttemptState.DISPATCH_INTENT);
            var report = RecoveryScanner.scan(s);
            assertEquals(1, report.escalated());
            var recovered = s.byId(a.attemptId()).orElseThrow();
            assertEquals(AttemptState.ESCALATED, recovered.state());
            assertTrue(recovered.reason().contains("human"),
                "no-capability reconcile must demand human confirmation: " + recovered.reason());
        }
    }

    @Test
    void executionReportedMovesToVerificationPending() {
        try (var s = store()) {
            var a = driveTo(s, bashLike(), "act-1", AttemptState.EXECUTION_REPORTED);
            var report = RecoveryScanner.scan(s);
            assertEquals(1, report.verificationPending());
            assertEquals(AttemptState.VERIFICATION_PENDING,
                s.byId(a.attemptId()).orElseThrow().state());
        }
    }

    @Test
    void scanIsIdempotent() throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "new content", StandardCharsets.UTF_8);
        try (var s = store()) {
            driveTo(s, fileWrite(file, "new content", null), "act-1", AttemptState.DISPATCH_INTENT);
            RecoveryScanner.scan(s);
            var second = RecoveryScanner.scan(s);
            assertEquals(0, second.movedToUnknown() + second.cancelledNoEffect()
                + second.reconciledEffectPresent() + second.confirmedNoEffect()
                + second.escalated());
        }
    }
}
