package com.clawkit.reliability.gate;

import com.clawkit.reliability.attempt.ActionAttempt;
import com.clawkit.reliability.attempt.AttemptState;
import com.clawkit.reliability.attempt.FileActionAttemptStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动恢复扫描（P1-G5）。
 *
 * <p>进程重启后对非终态 Attempt 收敛：
 * <ul>
 *   <li>intent 落盘前的状态 → CANCELLED_NO_EFFECT（确认未派发）；</li>
 *   <li>DISPATCH_INTENT → OUTCOME_UNKNOWN（落盘后即使未真正发送也按可能已发送处理）→ reconcile；</li>
 *   <li>EXECUTION_REPORTED → VERIFICATION_PENDING；</li>
 *   <li>OUTCOME_UNKNOWN / RECONCILING → 重新采证 reconcile；</li>
 *   <li>VERIFYING → 回到 VERIFICATION_PENDING（验证中断可重跑）。</li>
 * </ul>
 * reconcile 只用新采集的确定性证据：expected effects 成立 → 效果已发生（进入验证收敛）；
 * precondition 原样 → 确认未生效（FAILED_NO_EFFECT，允许有界重试）；
 * 两者都不成立或无 reconcile 能力 → 升级人工（ESCALATED）。
 */
public final class RecoveryScanner {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScanner.class);

    public record RecoveryReport(
        int scanned, int cancelledNoEffect, int movedToUnknown,
        int reconciledEffectPresent, int confirmedNoEffect,
        int verificationPending, int escalated
    ) {}

    private RecoveryScanner() {}

    public static RecoveryReport scan(FileActionAttemptStore store) {
        int cancelled = 0;
        int toUnknown = 0;
        int verified = 0;
        int noEffect = 0;
        int pending = 0;
        int escalated = 0;

        List<ActionAttempt> open = store.nonTerminal();
        for (ActionAttempt attempt : open) {
            try {
                switch (attempt.state()) {
                    case CREATED, WAITING_APPROVAL, PRECHECKING, READY -> {
                        store.transition(attempt.attemptId(), attempt.version(),
                            AttemptState.CANCELLED_NO_EFFECT,
                            "process restart before dispatch intent");
                        cancelled++;
                    }
                    case DISPATCH_INTENT -> {
                        var unknown = store.transition(attempt.attemptId(), attempt.version(),
                            AttemptState.OUTCOME_UNKNOWN,
                            "crash window: durable intent found on restart");
                        toUnknown++;
                        switch (reconcile(store, unknown)) {
                            case PENDING -> {
                                verified++;
                                pending++;
                            }
                            case NO_EFFECT -> noEffect++;
                            case ESCALATED -> escalated++;
                        }
                    }
                    case EXECUTION_REPORTED -> {
                        store.transition(attempt.attemptId(), attempt.version(),
                            AttemptState.VERIFICATION_PENDING,
                            "restart: execution reported, awaiting verification");
                        pending++;
                    }
                    case OUTCOME_UNKNOWN, RECONCILING -> {
                        switch (reconcile(store, attempt)) {
                            case PENDING -> {
                                verified++;
                                pending++;
                            }
                            case NO_EFFECT -> noEffect++;
                            case ESCALATED -> escalated++;
                        }
                    }
                    case VERIFYING -> {
                        store.transition(attempt.attemptId(), attempt.version(),
                            AttemptState.VERIFICATION_PENDING,
                            "restart: verification interrupted, re-queue");
                        pending++;
                    }
                    case VERIFICATION_PENDING, COMPENSATION_PENDING -> {
                        // 等待独立 Verification / 补偿 / 人工，不在启动扫描改变
                    }
                    default -> { /* terminal — 不在 nonTerminal() 中 */ }
                }
            } catch (RuntimeException e) {
                log.warn("[Recovery] attempt {} scan failed: {}", attempt.attemptId(), e.getMessage());
            }
        }
        RecoveryReport report = new RecoveryReport(open.size(), cancelled, toUnknown,
            verified, noEffect, pending, escalated);
        if (open.size() > 0) {
            log.info("[Recovery] {}", report);
        }
        return report;
    }

    private enum ReconcileOutcome { PENDING, NO_EFFECT, ESCALATED }

    /** 重新采证：只依赖确定性断言，不复用旧输出，不自动重复写。 */
    private static ReconcileOutcome reconcile(FileActionAttemptStore store, ActionAttempt attempt) {
        ActionAttempt reconciling = attempt.state() == AttemptState.RECONCILING
            ? attempt
            : store.transition(attempt.attemptId(), attempt.version(),
                AttemptState.RECONCILING, "reconciling with fresh evidence");

        var descriptor = reconciling.descriptor();
        boolean canReconcile = descriptor.reliability().reconcileSupported()
            && !descriptor.expectedEffects().isEmpty();
        if (canReconcile) {
            var expected = DeterministicVerifier.verify(descriptor.expectedEffects());
            if (expected.passed()) {
                // Reconcile 只确认“效果存在”，最终成功必须由独立 Verification Run 收敛。
                store.transition(reconciling.attemptId(), reconciling.version(),
                    AttemptState.VERIFICATION_PENDING,
                    "reconcile: expected effects present; independent verification required");
                return ReconcileOutcome.PENDING;
            }
            var pre = DeterministicVerifier.verify(preconditionsAsAssertions(descriptor.preconditions()));
            if (!descriptor.preconditions().isEmpty() && pre.passed()) {
                store.transition(reconciling.attemptId(), reconciling.version(),
                    AttemptState.FAILED_NO_EFFECT,
                    "reconcile: precondition intact, action confirmed not applied");
                return ReconcileOutcome.NO_EFFECT;
            }
        }
        store.transition(reconciling.attemptId(), reconciling.version(),
            AttemptState.ESCALATED,
            canReconcile
                ? "reconcile inconclusive: state drifted from both expected and pre-action"
                : "no reconcile capability declared; human confirmation required");
        return ReconcileOutcome.ESCALATED;
    }

    /** precondition 断言转换：pre-file-sha256/pre-file-absent → 可执行断言。 */
    private static List<String> preconditionsAsAssertions(List<String> preconditions) {
        List<String> assertions = new ArrayList<>();
        for (String pre : preconditions) {
            if (pre.startsWith("pre-file-sha256:")) {
                assertions.add("file-sha256:" + pre.substring("pre-file-sha256:".length()));
            } else if (pre.startsWith("pre-file-absent:")) {
                assertions.add("file-absent:" + pre.substring("pre-file-absent:".length()));
            } else {
                assertions.add("unsupported:" + pre); // fail closed
            }
        }
        return assertions;
    }
}
