package com.clawkit.reliability.attempt;

import com.clawkit.tools.action.ActionDescriptor;
import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.action.FailureClass;
import com.clawkit.tools.action.VerificationMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Attempt 生命周期协调器（P1-G3）：
 * 幂等、次数、冷却、目标互斥、durable DISPATCH_INTENT 与结果归类的唯一入口。
 *
 * <p>LLM 不参与任何状态决策；确定性映射来自 {@link FailureClass} 与
 * {@link com.clawkit.reliability.FailureDecisionTable}。
 */
public final class ActionAttemptCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ActionAttemptCoordinator.class);

    /** attempt 迁移事件（观测投影用；失败不得影响控制面）。 */
    public interface AttemptEventSink extends BiConsumer<ActionAttempt, String> {}

    private final FileActionAttemptStore store;
    private final AttemptPolicy policy;
    private final AttemptEventSink eventSink;

    public ActionAttemptCoordinator(FileActionAttemptStore store, AttemptPolicy policy,
                                    AttemptEventSink eventSink) {
        this.store = Objects.requireNonNull(store, "store required");
        this.policy = policy != null ? policy : AttemptPolicy.defaults();
        this.eventSink = eventSink != null ? eventSink : (a, r) -> {};
    }

    /** 次数与冷却策略。 */
    public record AttemptPolicy(int maxAttempts, Duration cooldown) {
        public static AttemptPolicy defaults() {
            return new AttemptPolicy(3, Duration.ofSeconds(0));
        }
    }

    /** 执行凭据：持有最新 version，迟到调用（旧 version）会被 store CAS 拒绝。 */
    public static final class AttemptTicket {
        private final String attemptId;
        private long version;

        private AttemptTicket(String attemptId, long version) {
            this.attemptId = attemptId;
            this.version = version;
        }

        public String attemptId() { return attemptId; }
        public long version() { return version; }
    }

    // ── 生命周期 ─────────────────────────────────────────────────

    /**
     * 开始一次副作用 Attempt：
     * 派发不确定（未收敛）的同逻辑动作幂等重放返回既有 ticket，不重复创建；
     * 目标互斥 / 连续无效果次数 / 冷却违规抛出对应异常；
     * approved=false 时停在 WAITING_APPROVAL（由调用方走审批后再 approve()）。
     */
    public AttemptTicket begin(ActionDescriptor descriptor, String logicalActionId,
                               String runId, boolean approved) {
        String actionId = logicalActionId != null && !logicalActionId.isBlank()
            ? logicalActionId : descriptor.contentDerivedActionId();

        List<ActionAttempt> prior = store.byLogicalAction(actionId);
        // 幂等重放：派发状态不确定的既有 Attempt → 返回其 ticket（禁止重复提交）。
        // 已确认效果并进入验证链的 Attempt 属于已完成的历史 occurrence，允许新 Attempt。
        for (ActionAttempt p : prior) {
            if (dispatchUncertain(p.state())) {
                if (!p.identity().targetKey().equals(descriptor.targetKey())
                        || !p.identity().actionFingerprint().equals(descriptor.fingerprint())) {
                    throw new AttemptFailure.IdempotencyConflictException(
                        p.identity().idempotencyKey());
                }
                log.info("[Attempt] idempotent replay of {} in state {}", p.attemptId(), p.state());
                return new AttemptTicket(p.attemptId(), p.version());
            }
        }
        // 次数上限按"连续无效果失败"计：成功 occurrence 不累积
        int trailingNoEffect = 0;
        for (int i = prior.size() - 1; i >= 0; i--) {
            AttemptState s = prior.get(i).state();
            if (s == AttemptState.FAILED_NO_EFFECT || s == AttemptState.CANCELLED_NO_EFFECT) {
                trailingNoEffect++;
            } else {
                break;
            }
        }
        if (trailingNoEffect >= policy.maxAttempts()) {
            throw new AttemptFailure.MaxAttemptsExceededException(actionId, policy.maxAttempts());
        }
        if (!policy.cooldown().isZero()) {
            Instant latest = store.byTarget(descriptor.targetKey()).stream()
                .map(ActionAttempt::updatedAt)
                .max(Instant::compareTo).orElse(null);
            if (latest != null && latest.plus(policy.cooldown()).isAfter(Instant.now())) {
                throw new AttemptFailure.CooldownActiveException(
                    descriptor.targetKey(), latest.plus(policy.cooldown()));
            }
        }

        ActionAttempt attempt = store.create(descriptor, actionId, prior.size() + 1,
            runId, null, "ACTION");
        AttemptTicket ticket = new AttemptTicket(attempt.attemptId(), attempt.version());
        emit(attempt, "created");
        if (approved) {
            advance(ticket, AttemptState.PRECHECKING, "auto/pre-approved");
        } else {
            advance(ticket, AttemptState.WAITING_APPROVAL, "awaiting approval");
        }
        return ticket;
    }

    /** 派发状态不确定：重复提交这些 Attempt 可能造成重复副作用。 */
    private static boolean dispatchUncertain(AttemptState state) {
        return switch (state) {
            case CREATED, WAITING_APPROVAL, PRECHECKING, READY,
                 DISPATCH_INTENT, OUTCOME_UNKNOWN, RECONCILING -> true;
            default -> false;
        };
    }

    /** 审批通过（授权绑定 fingerprint 的校验由调用方完成后调用）。 */
    public void approve(AttemptTicket ticket) {
        advance(ticket, AttemptState.PRECHECKING, "approved");
    }

    /** 锁内重新采集的 precheck 通过后，才允许写入 durable dispatch intent。 */
    public ActionAttempt completePrecheck(AttemptTicket ticket, boolean passed, String evidence) {
        ActionAttempt current = store.byId(ticket.attemptId()).orElseThrow();
        if (current.state() == AttemptState.READY && passed) {
            ticket.version = current.version();
            return current;
        }
        return advance(ticket, passed ? AttemptState.READY : AttemptState.FAILED_NO_EFFECT,
            (passed ? "precheck passed: " : "precheck failed: ") + evidence);
    }

    /** 审批拒绝 → 确认无副作用终态。 */
    public void reject(AttemptTicket ticket, String reason) {
        advance(ticket, AttemptState.FAILED_NO_EFFECT, "approval rejected: " + reason);
    }

    /** 派发前取消 → 确认无副作用终态。 */
    public void cancelBeforeDispatch(AttemptTicket ticket, String reason) {
        advance(ticket, AttemptState.CANCELLED_NO_EFFECT, reason);
    }

    /**
     * durable DISPATCH_INTENT：必须在真正调用工具前完成落盘（force）。
     * 此后崩溃/超时/取消一律按可能已发送处理。
     */
    public void markDispatchIntent(AttemptTicket ticket) {
        advance(ticket, AttemptState.DISPATCH_INTENT, "dispatching");
    }

    /**
     * 按副作用确定性归类执行结果（确定性决策表，LLM 不可修改）：
     * EFFECT_CONFIRMED → VERIFICATION_PENDING；
     * NO_EFFECT_CONFIRMED / NOT_DISPATCHED → FAILED_NO_EFFECT；
     * PARTIAL / UNKNOWN → 保持锁定（VERIFICATION_PENDING / OUTCOME_UNKNOWN）。
     */
    public ActionAttempt reportOutcome(AttemptTicket ticket, EffectCertainty certainty,
                                       FailureClass failureClass, String reason) {
        Objects.requireNonNull(certainty, "certainty required");
        String detail = (failureClass != null ? failureClass + ": " : "") + (reason != null ? reason : "");
        return switch (certainty) {
            case EFFECT_CONFIRMED -> {
                advance(ticket, AttemptState.EXECUTION_REPORTED, detail);
                yield advance(ticket, AttemptState.VERIFICATION_PENDING, "awaiting verification");
            }
            case PARTIAL_EFFECT -> {
                advance(ticket, AttemptState.EXECUTION_REPORTED, "partial: " + detail);
                yield advance(ticket, AttemptState.VERIFICATION_PENDING,
                    "partial effect requires verification/compensation");
            }
            case NO_EFFECT_CONFIRMED, NOT_DISPATCHED ->
                advance(ticket, AttemptState.FAILED_NO_EFFECT, detail);
            case EFFECT_UNKNOWN ->
                advance(ticket, AttemptState.OUTCOME_UNKNOWN, detail);
        };
    }

    /** 进入验证。 */
    public void startVerification(AttemptTicket ticket) {
        advance(ticket, AttemptState.VERIFYING, "verification started");
    }

    /** 验证基础设施失败或被取消，回队等待安全重跑，不据此判定动作失败。 */
    public ActionAttempt deferVerification(AttemptTicket ticket, String evidence) {
        return advance(ticket, AttemptState.VERIFICATION_PENDING,
            "verification deferred: " + evidence);
    }

    /**
     * 确定性/工作流验证结论。
     * MANUAL_REQUIRED 不得自动进入 VERIFIED_SUCCESS——回到 VERIFICATION_PENDING 等待人工。
     */
    public ActionAttempt completeVerification(AttemptTicket ticket, VerificationMode mode,
                                              boolean passed, String evidence) {
        if (mode == VerificationMode.MANUAL_REQUIRED) {
            return advance(ticket, AttemptState.VERIFICATION_PENDING,
                "MANUAL_REQUIRED: automatic VERIFIED_SUCCESS is forbidden; " + evidence);
        }
        if (passed) {
            return advance(ticket, AttemptState.VERIFIED_SUCCESS, evidence);
        }
        return advance(ticket, AttemptState.COMPENSATION_PENDING,
            "verification failed: " + evidence);
    }

    /** 人工确认（唯一允许把 MANUAL_REQUIRED 动作标记成功的路径）。 */
    public ActionAttempt manualConfirm(AttemptTicket ticket, boolean success,
                                       String operator, String note) {
        advance(ticket, AttemptState.VERIFYING, "manual confirmation by " + operator);
        if (success) {
            return advance(ticket, AttemptState.VERIFIED_SUCCESS,
                "manually confirmed by " + operator + ": " + note);
        }
        return advance(ticket, AttemptState.ESCALATED,
            "manually rejected by " + operator + ": " + note);
    }

    /** 升级人工接管。 */
    public ActionAttempt escalate(AttemptTicket ticket, String reason) {
        ActionAttempt current = store.byId(ticket.attemptId()).orElseThrow();
        if (current.state().isTerminal()) return current;
        if (current.state().canTransitionTo(AttemptState.ESCALATED)) {
            return advance(ticket, AttemptState.ESCALATED, reason);
        }
        // 中间状态先收敛到可升级状态
        if (current.state() == AttemptState.DISPATCH_INTENT) {
            advance(ticket, AttemptState.OUTCOME_UNKNOWN, "escalating: " + reason);
        }
        return advance(ticket, AttemptState.ESCALATED, reason);
    }

    /**
     * 补偿：不修改原 Attempt，创建一个关联原 Attempt 的新副作用 Attempt，
     * 同样接受全部门禁（互斥/次数/冷却）。
     */
    public AttemptTicket beginCompensation(AttemptTicket original,
                                           ActionDescriptor compensationDescriptor,
                                           String runId) {
        ActionAttempt origin = store.byId(original.attemptId()).orElseThrow();
        if (origin.state() != AttemptState.COMPENSATION_PENDING) {
            throw new AttemptFailure.IllegalTransitionException(
                origin.attemptId(), origin.state(), AttemptState.COMPENSATION_PENDING);
        }
        ActionAttempt attempt = store.create(compensationDescriptor,
            compensationDescriptor.contentDerivedActionId(), 1, runId,
            origin.attemptId(), "COMPENSATION");
        AttemptTicket ticket = new AttemptTicket(attempt.attemptId(), attempt.version());
        emit(attempt, "compensation created for " + origin.attemptId());
        advance(ticket, AttemptState.PRECHECKING, "compensation precheck");
        return ticket;
    }

    /** 补偿 Attempt 验证通过后，关闭原 Attempt。 */
    public ActionAttempt closeCompensated(AttemptTicket original, String compensationAttemptId) {
        ActionAttempt compensation = store.byId(compensationAttemptId).orElseThrow();
        if (!original.attemptId().equals(compensation.relatedAttemptId())
                || !"COMPENSATION".equals(compensation.purpose())
                || compensation.state() != AttemptState.VERIFIED_SUCCESS) {
            throw new IllegalStateException(
                "compensation attempt must be linked and VERIFIED_SUCCESS");
        }
        return advance(original, AttemptState.COMPENSATED,
            "compensated by " + compensationAttemptId);
    }

    public FileActionAttemptStore store() {
        return store;
    }

    /** 为恢复流程取得绑定当前 version 的 ticket。 */
    public AttemptTicket ticketFor(String attemptId) {
        ActionAttempt current = store.byId(attemptId).orElseThrow();
        return new AttemptTicket(current.attemptId(), current.version());
    }

    // ── 内部 ────────────────────────────────────────────────────

    private ActionAttempt advance(AttemptTicket ticket, AttemptState to, String reason) {
        ActionAttempt next = store.transition(ticket.attemptId, ticket.version, to, reason);
        ticket.version = next.version();
        emit(next, reason);
        return next;
    }

    private void emit(ActionAttempt attempt, String reason) {
        try {
            eventSink.accept(attempt, reason);
        } catch (Exception e) {
            // 观测失败不得改变控制面状态
            log.warn("attempt event sink failed: {}", e.getMessage());
        }
    }
}
