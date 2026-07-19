package com.clawkit.reliability.attempt;

import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.action.ActionDescriptor;
import com.clawkit.tools.action.ActionReliability;
import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.action.FailureClass;
import com.clawkit.tools.action.Reversibility;
import com.clawkit.tools.action.VerificationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActionAttemptCoordinatorTest {

    @TempDir
    Path dir;

    private ActionAttemptCoordinator coordinator(int maxAttempts, Duration cooldown) {
        return new ActionAttemptCoordinator(new FileActionAttemptStore(dir),
            new ActionAttemptCoordinator.AttemptPolicy(maxAttempts, cooldown), null);
    }

    private static ActionDescriptor deterministic(String target) {
        return new ActionDescriptor("file.write", target, "d1",
            ToolRiskLevel.HIGH, Reversibility.REVERSIBLE,
            ActionReliability.idempotentSetter(), VerificationMode.DETERMINISTIC,
            List.of(), List.of(), "", "");
    }

    private static ActionDescriptor manual(String target) {
        return new ActionDescriptor("bash.exec", target, "d1",
            ToolRiskLevel.HIGH, Reversibility.IRREVERSIBLE,
            ActionReliability.none(), VerificationMode.MANUAL_REQUIRED,
            List.of(), List.of(), "", "");
    }

    private static void passPrecheck(ActionAttemptCoordinator coordinator,
                                     ActionAttemptCoordinator.AttemptTicket ticket) {
        coordinator.completePrecheck(ticket, true, "fresh evidence");
    }

    @Test
    void happyPathReachesVerifiedSuccessOnlyAfterVerification() {
        var c = coordinator(3, Duration.ZERO);
        var ticket = c.begin(deterministic("file:/a"), null, "run-1", true);
        passPrecheck(c, ticket);
        c.markDispatchIntent(ticket);
        var reported = c.reportOutcome(ticket, EffectCertainty.EFFECT_CONFIRMED, FailureClass.NONE, "written");
        assertEquals(AttemptState.VERIFICATION_PENDING, reported.state());
        assertThrows(AttemptFailure.TargetBusyException.class, () ->
            c.begin(deterministic("file:/a"), "another-action", "run-1", true),
            "same-target writes must remain blocked until verification settles");
        c.startVerification(ticket);
        var done = c.completeVerification(ticket, VerificationMode.DETERMINISTIC, true, "hash match");
        assertEquals(AttemptState.VERIFIED_SUCCESS, done.state());
        // 目标释放
        assertTrue(c.store().activeOnTarget("file:/a").isEmpty());
    }

    @Test
    void unknownOutcomeIsStickyAndBlocksNewWrites() {
        var c = coordinator(3, Duration.ZERO);
        var ticket = c.begin(deterministic("file:/a"), "act-1", "run-1", true);
        passPrecheck(c, ticket);
        c.markDispatchIntent(ticket);
        var unknown = c.reportOutcome(ticket, EffectCertainty.EFFECT_UNKNOWN,
            FailureClass.TIMEOUT_OUTCOME_UNKNOWN, "timeout");
        assertEquals(AttemptState.OUTCOME_UNKNOWN, unknown.state());
        // 结果未知后：同目标新写被互斥拒绝（不自动重复写）
        assertThrows(AttemptFailure.TargetBusyException.class, () ->
            c.begin(deterministic("file:/a"), "act-2", "run-1", true));
        // 幂等重放同一逻辑动作 → 返回原 ticket，且无法重新 dispatch
        var replay = c.begin(deterministic("file:/a"), "act-1", "run-1", true);
        assertEquals(ticket.attemptId(), replay.attemptId());
        assertThrows(AttemptFailure.IllegalTransitionException.class, () ->
            c.markDispatchIntent(replay));
    }

    @Test
    void confirmedNoEffectReleasesTargetAndAllowsBoundedRetry() {
        var c = coordinator(2, Duration.ZERO);
        var t1 = c.begin(deterministic("file:/a"), "act-1", "run-1", true);
        passPrecheck(c, t1);
        c.markDispatchIntent(t1);
        var failed = c.reportOutcome(t1, EffectCertainty.NO_EFFECT_CONFIRMED,
            FailureClass.LOCAL_ERROR_NO_EFFECT, "pre-write check failed");
        assertEquals(AttemptState.FAILED_NO_EFFECT, failed.state());

        // 第二次尝试允许（attemptSeq=2）
        var t2 = c.begin(deterministic("file:/a"), "act-1", "run-1", true);
        assertNotEquals(t1.attemptId(), t2.attemptId());
        passPrecheck(c, t2);
        c.markDispatchIntent(t2);
        c.reportOutcome(t2, EffectCertainty.NO_EFFECT_CONFIRMED,
            FailureClass.LOCAL_ERROR_NO_EFFECT, "again");

        // 第三次超过 maxAttempts=2 → 拒绝
        assertThrows(AttemptFailure.MaxAttemptsExceededException.class, () ->
            c.begin(deterministic("file:/a"), "act-1", "run-1", true));
    }

    @Test
    void cooldownBlocksRapidReattemptsOnSameTarget() {
        var c = coordinator(5, Duration.ofMinutes(10));
        var t1 = c.begin(deterministic("file:/a"), "act-1", "run-1", true);
        c.cancelBeforeDispatch(t1, "user cancel");
        assertThrows(AttemptFailure.CooldownActiveException.class, () ->
            c.begin(deterministic("file:/a"), "act-2", "run-1", true));
    }

    @Test
    void manualRequiredNeverAutoVerifiedSuccess() {
        var c = coordinator(3, Duration.ZERO);
        var ticket = c.begin(manual("shell:/ws"), null, "run-1", true);
        passPrecheck(c, ticket);
        c.markDispatchIntent(ticket);
        c.reportOutcome(ticket, EffectCertainty.EFFECT_CONFIRMED, FailureClass.NONE, "exit=0");
        c.startVerification(ticket);
        var after = c.completeVerification(ticket, VerificationMode.MANUAL_REQUIRED, true, "looks fine");
        assertEquals(AttemptState.VERIFICATION_PENDING, after.state(),
            "MANUAL_REQUIRED 不得自动进入 VERIFIED_SUCCESS");
        // 只有人工确认才能到达 VERIFIED_SUCCESS
        var confirmed = c.manualConfirm(ticket, true, "operator-1", "checked manually");
        assertEquals(AttemptState.VERIFIED_SUCCESS, confirmed.state());
    }

    @Test
    void approvalRejectionEndsWithNoEffect() {
        var c = coordinator(3, Duration.ZERO);
        var ticket = c.begin(deterministic("file:/a"), null, "run-1", false);
        c.reject(ticket, "user said no");
        var attempt = c.store().byId(ticket.attemptId()).orElseThrow();
        assertEquals(AttemptState.FAILED_NO_EFFECT, attempt.state());
        assertTrue(c.store().activeOnTarget("file:/a").isEmpty());
    }

    @Test
    void lateReportCannotOverrideHumanEscalation() {
        var c = coordinator(3, Duration.ZERO);
        var ticket = c.begin(deterministic("file:/a"), "act-1", "run-1", true);
        passPrecheck(c, ticket);
        c.markDispatchIntent(ticket);
        c.reportOutcome(ticket, EffectCertainty.EFFECT_UNKNOWN,
            FailureClass.CONNECTION_LOST, "connection lost");
        // 人工接管
        var escalated = c.escalate(ticket, "operator takeover");
        assertEquals(AttemptState.ESCALATED, escalated.state());
        // 迟到的执行结果（旧语义重放）不能反转人工结论
        assertThrows(RuntimeException.class, () ->
            c.reportOutcome(ticket, EffectCertainty.EFFECT_CONFIRMED, FailureClass.NONE, "late response"));
        assertEquals(AttemptState.ESCALATED,
            c.store().byId(ticket.attemptId()).orElseThrow().state());
    }

    @Test
    void compensationIsANewLinkedAttemptUnderSameGates() {
        var c = coordinator(5, Duration.ZERO);
        var ticket = c.begin(deterministic("file:/a"), "act-1", "run-1", true);
        passPrecheck(c, ticket);
        c.markDispatchIntent(ticket);
        c.reportOutcome(ticket, EffectCertainty.EFFECT_CONFIRMED, FailureClass.NONE, "written");
        c.startVerification(ticket);
        var failedVerify = c.completeVerification(ticket, VerificationMode.DETERMINISTIC,
            false, "hash mismatch");
        assertEquals(AttemptState.COMPENSATION_PENDING, failedVerify.state());

        // 补偿可原子接管原 Attempt 持有的同一 target 锁。
        var compDescriptor = new ActionDescriptor("file.restore", "file:/a", "d2",
            ToolRiskLevel.HIGH, Reversibility.IRREVERSIBLE, ActionReliability.none(),
            VerificationMode.DETERMINISTIC, List.of(), List.of(), "", "");
        var compTicket = c.beginCompensation(ticket, compDescriptor, "run-1");
        var comp = c.store().byId(compTicket.attemptId()).orElseThrow();
        assertEquals("COMPENSATION", comp.purpose());
        assertEquals(ticket.attemptId(), comp.relatedAttemptId());

        // 补偿完成后关闭原 Attempt
        passPrecheck(c, compTicket);
        c.markDispatchIntent(compTicket);
        c.reportOutcome(compTicket, EffectCertainty.EFFECT_CONFIRMED, FailureClass.NONE, "restored");
        c.startVerification(compTicket);
        c.completeVerification(compTicket, VerificationMode.DETERMINISTIC, true, "hash ok");
        var closed = c.closeCompensated(ticket, compTicket.attemptId());
        assertEquals(AttemptState.COMPENSATED, closed.state());
    }

    @Test
    void restartReplayResumesInFlightAttempt() {
        ActionAttemptCoordinator.AttemptTicket ticket;
        var store1 = new FileActionAttemptStore(dir);
        var c1 = new ActionAttemptCoordinator(store1,
            new ActionAttemptCoordinator.AttemptPolicy(3, Duration.ZERO), null);
        ticket = c1.begin(deterministic("file:/a"), "act-1", "run-1", true);
        passPrecheck(c1, ticket);
        c1.markDispatchIntent(ticket);
        store1.close(); // 进程"崩溃"于 DISPATCH_INTENT 之后

        var store2 = new FileActionAttemptStore(dir);
        var c2 = new ActionAttemptCoordinator(store2,
            new ActionAttemptCoordinator.AttemptPolicy(3, Duration.ZERO), null);
        // 重启重放：同 logicalActionId 返回同一 Attempt，不重复执行
        var replay = c2.begin(deterministic("file:/a"), "act-1", "run-1", true);
        assertEquals(ticket.attemptId(), replay.attemptId());
        var attempt = store2.byId(replay.attemptId()).orElseThrow();
        assertEquals(AttemptState.DISPATCH_INTENT, attempt.state());
        // 不允许再次 dispatch（可能已发送）
        assertThrows(AttemptFailure.IllegalTransitionException.class, () ->
            c2.markDispatchIntent(replay));
        store2.close();
    }
}
