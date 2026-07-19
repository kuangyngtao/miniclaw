package com.clawkit.reliability.gate;

import com.clawkit.reliability.attempt.ActionAttemptCoordinator;
import com.clawkit.reliability.attempt.ActionAttemptCoordinator.AttemptTicket;
import com.clawkit.reliability.attempt.ActionAttempt;
import com.clawkit.reliability.attempt.AttemptFailure;
import com.clawkit.tools.ToolError;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolExecutionStatus;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.action.ActionDescriptor;
import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.action.FailureClass;
import com.clawkit.tools.action.VerificationMode;
import com.clawkit.tools.control.ExecutionControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 副作用统一门禁（P1-G4）：ToolCallExecutor 的唯一 Side Effect Gate。
 *
 * <p>不变量：
 * <ul>
 *   <li>无 ActionDescriptor 的副作用调用 fail closed；</li>
 *   <li>Reliability Journal 不可写时阻断全部写动作；</li>
 *   <li>durable DISPATCH_INTENT 先于真实执行；</li>
 *   <li>结果按 EffectCertainty 归类，结果未知禁止自动重复写；</li>
 *   <li>DETERMINISTIC 验证以新采集证据执行；MANUAL_REQUIRED 永不自动 VERIFIED_SUCCESS。</li>
 * </ul>
 */
public final class SideEffectGate {

    private static final Logger log = LoggerFactory.getLogger(SideEffectGate.class);

    private final ActionAttemptCoordinator coordinator;
    private final VerificationHandler verificationHandler;

    public SideEffectGate(ActionAttemptCoordinator coordinator) {
        this(coordinator, null);
    }

    public SideEffectGate(ActionAttemptCoordinator coordinator,
                          VerificationHandler verificationHandler) {
        this.coordinator = coordinator;
        this.verificationHandler = verificationHandler;
    }

    @FunctionalInterface
    public interface VerificationHandler {
        VerificationOutcome verify(ActionAttempt attempt);
    }

    public record VerificationOutcome(boolean passed, String evidence) {}

    public ActionAttemptCoordinator coordinator() {
        return coordinator;
    }

    /** 启动恢复后，收敛可自动验证且仍在队列中的 Attempt。 */
    public int verifyPendingAttempts() {
        if (verificationHandler == null) {
            return 0;
        }
        int completed = 0;
        for (ActionAttempt attempt : coordinator.store().nonTerminal()) {
            if (attempt.state() != com.clawkit.reliability.attempt.AttemptState.VERIFICATION_PENDING
                    || attempt.descriptor().verificationMode() == VerificationMode.MANUAL_REQUIRED) {
                continue;
            }
            AttemptTicket ticket = coordinator.ticketFor(attempt.attemptId());
            try {
                coordinator.startVerification(ticket);
                VerificationOutcome outcome = verificationHandler.verify(attempt);
                coordinator.completeVerification(ticket, attempt.descriptor().verificationMode(),
                    outcome.passed(), outcome.evidence());
                completed++;
            } catch (Exception e) {
                try {
                    coordinator.deferVerification(ticket, e.getMessage());
                } catch (RuntimeException deferred) {
                    log.error("[Gate] recovery verification could not be deferred for {}: {}",
                        attempt.attemptId(), deferred.getMessage());
                }
            }
        }
        return completed;
    }

    /**
     * 用 Attempt 生命周期包裹一次副作用工具执行。
     *
     * @param descriptor 工具生成的动作描述；null → fail closed
     * @param toolCallId 本次调用 id（仅用于构造结果）
     * @param toolName   工具名
     * @param meta       冻结的工具 metadata
     * @param control    执行控制（派发前取消检查）
     * @param runId      当前 runId
     * @param body       真实执行体
     */
    public ToolExecutionResult execute(ActionDescriptor descriptor,
                                       String toolCallId, String toolName,
                                       ToolMetadata meta, ExecutionControl control,
                                       String runId,
                                       Supplier<ToolExecutionResult> body) {
        if (descriptor == null) {
            return blocked(toolCallId, toolName, meta, "T-SEG-001",
                "副作用工具未提供可信 ActionDescriptor，按 fail-closed 拒绝执行。"
                + "工具必须实现 describeAction()。", FailureClass.PRECONDITION_FAILED);
        }

        AttemptTicket ticket;
        try {
            ticket = coordinator.begin(descriptor, null, runId, true);
        } catch (AttemptFailure.TargetBusyException e) {
            return blocked(toolCallId, toolName, meta, "T-SEG-002",
                "目标 " + e.targetKey() + " 正被 attempt " + e.holderAttemptId()
                + " 锁定（执行中/结果未知/待补偿）。禁止并发或未知结果下的重复写；"
                + "请先重新采证确认状态。", FailureClass.PRECONDITION_FAILED);
        } catch (AttemptFailure.MaxAttemptsExceededException e) {
            return blocked(toolCallId, toolName, meta, "T-SEG-003",
                "该动作连续无效果失败已达次数上限：" + e.getMessage()
                + "。停止自动重试，请改变方案或升级人工。", FailureClass.PRECONDITION_FAILED);
        } catch (AttemptFailure.CooldownActiveException e) {
            return blocked(toolCallId, toolName, meta, "T-SEG-004",
                "目标处于冷却窗口：" + e.getMessage(), FailureClass.PRECONDITION_FAILED);
        } catch (AttemptFailure.IdempotencyConflictException e) {
            return blocked(toolCallId, toolName, meta, "T-SEG-009",
                "幂等键与动作指纹冲突，拒绝重放：" + e.getMessage(),
                FailureClass.PRECONDITION_FAILED);
        } catch (AttemptFailure.StoreUnavailableException e) {
            return blocked(toolCallId, toolName, meta, "T-SEG-005",
                "Reliability Journal 不可写，所有副作用动作已被阻断（fail closed）："
                + e.getMessage(), FailureClass.PRECONDITION_FAILED);
        }

        ActionAttempt current = coordinator.store().byId(ticket.attemptId()).orElseThrow();
        if (current.state() != com.clawkit.reliability.attempt.AttemptState.PRECHECKING) {
            return blocked(toolCallId, toolName, meta, "T-SEG-006",
                "该动作已有未收敛 attempt " + ticket.attemptId() + "（"
                    + current.state() + "），禁止自动重复执行。",
                FailureClass.PRECONDITION_FAILED)
                .withReliability(EffectCertainty.NOT_DISPATCHED,
                    FailureClass.PRECONDITION_FAILED, ticket.attemptId());
        }

        // 派发前取消：确认无副作用
        if (control != null && control.isCancelled()) {
            safeCancelBeforeDispatch(ticket, "cancelled before dispatch");
            return ToolExecutionResult.cancelled(toolCallId, toolName,
                "执行已被取消，动作未派发。", 0, meta, true)
                .withReliability(null, FailureClass.CANCELLED_BEFORE_DISPATCH, ticket.attemptId());
        }

        DeterministicVerifier.Verdict precheck =
            DeterministicVerifier.verifyPreconditions(descriptor.preconditions());
        try {
            coordinator.completePrecheck(ticket, precheck.passed(), precheck.detail());
        } catch (RuntimeException e) {
            return bookkeepingFailed(toolCallId, toolName, meta, ticket,
                "precheck journal update failed: " + e.getMessage());
        }
        if (!precheck.passed()) {
            return blocked(toolCallId, toolName, meta, "T-SEG-007",
                "前置条件在目标锁内复验失败：" + precheck.detail(),
                FailureClass.PRECONDITION_FAILED)
                .withReliability(EffectCertainty.NOT_DISPATCHED,
                    FailureClass.PRECONDITION_FAILED, ticket.attemptId());
        }

        // durable DISPATCH_INTENT：落盘后崩溃/超时一律按可能已发送处理
        try {
            coordinator.markDispatchIntent(ticket);
        } catch (AttemptFailure.IllegalTransitionException e) {
            return blocked(toolCallId, toolName, meta, "T-SEG-006",
                "该动作先前的结果未收敛（attempt " + ticket.attemptId()
                + "），禁止自动重复执行；需要 reconcile 或人工确认。",
                FailureClass.PRECONDITION_FAILED);
        } catch (AttemptFailure.StoreUnavailableException e) {
            return blocked(toolCallId, toolName, meta, "T-SEG-005",
                "DISPATCH_INTENT 无法落盘，动作被阻断（fail closed）：" + e.getMessage(),
                FailureClass.PRECONDITION_FAILED);
        }

        ToolExecutionResult result;
        try {
            result = body.get();
        } catch (Exception e) {
            // 执行体异常：副作用状态未知
            if (reportSafely(ticket, EffectCertainty.EFFECT_UNKNOWN,
                    FailureClass.EXECUTION_ERROR_OUTCOME_UNKNOWN, e.getMessage()) == null) {
                return bookkeepingFailed(toolCallId, toolName, meta, ticket,
                    "outcome journal update failed after dispatch");
            }
            return ToolExecutionResult.internalError(toolCallId, toolName,
                "执行异常，副作用状态未知：" + e.getMessage(), 0, meta)
                .withReliability(EffectCertainty.EFFECT_UNKNOWN,
                    FailureClass.EXECUTION_ERROR_OUTCOME_UNKNOWN, ticket.attemptId());
        }

        EffectCertainty certainty = result.effectCertainty() != null
            ? result.effectCertainty() : EffectCertainty.EFFECT_UNKNOWN;
        FailureClass failureClass = result.failureClass();
        ActionAttempt reported = reportSafely(ticket, certainty, failureClass,
            result.errorCode() != null ? result.errorCode() : result.status().name());
        if (reported == null) {
            return bookkeepingFailed(toolCallId, toolName, meta, ticket,
                "outcome journal update failed after dispatch; remote effect is unknown");
        }

        if (certainty == EffectCertainty.EFFECT_CONFIRMED
                || certainty == EffectCertainty.PARTIAL_EFFECT) {
            if (descriptor.verificationMode() == VerificationMode.MANUAL_REQUIRED
                    || verificationHandler == null) {
                return verificationPending(result, ticket, certainty, failureClass);
            }
            try {
                coordinator.startVerification(ticket);
                VerificationOutcome outcome = verificationHandler.verify(reported);
                coordinator.completeVerification(ticket, descriptor.verificationMode(),
                    outcome.passed(), outcome.evidence());
                if (!outcome.passed()) {
                    return verificationFailed(result, ticket, outcome.evidence());
                }
            } catch (Exception e) {
                log.warn("[Gate] verification bookkeeping failed for {}: {}",
                    ticket.attemptId(), e.getMessage());
                try {
                    coordinator.deferVerification(ticket, e.getMessage());
                } catch (RuntimeException deferred) {
                    return bookkeepingFailed(toolCallId, toolName, meta, ticket,
                        "verification journal update failed: " + deferred.getMessage());
                }
                return verificationPending(result, ticket, certainty, failureClass);
            }
        }

        return result.withReliability(certainty, failureClass, ticket.attemptId());
    }

    private ToolExecutionResult verificationPending(ToolExecutionResult result,
                                                    AttemptTicket ticket,
                                                    EffectCertainty certainty,
                                                    FailureClass failureClass) {
        String output = result.output()
            + "\n[Reliability] 动作已执行，但尚未完成独立验证；不得视为最终成功。";
        return new ToolExecutionResult(
            result.toolCallId(), result.toolName(), output,
            ToolExecutionStatus.VERIFICATION_PENDING, null,
            result.durationMs(), result.outputStats(), result.exitCode(),
            result.metadata(), result.approval(), result.auditId())
            .withReliability(certainty, failureClass, ticket.attemptId())
            .withOutputEnvelope(result.outputEnvelope());
    }

    private ToolExecutionResult verificationFailed(ToolExecutionResult result,
                                                   AttemptTicket ticket, String detail) {
        String output = result.output() + "\n[Reliability] 确定性验证失败：" + detail
            + "。动作效果与预期不符，attempt 已进入补偿等待。";
        return new ToolExecutionResult(
            result.toolCallId(), result.toolName(), output,
            ToolExecutionStatus.TOOL_ERROR,
            ToolError.fatal("VERIFICATION_FAILED", detail),
            result.durationMs(), result.outputStats(), result.exitCode(),
            result.metadata(), result.approval(), result.auditId())
            .withReliability(EffectCertainty.PARTIAL_EFFECT,
                FailureClass.PARTIAL_EXECUTION, ticket.attemptId())
            .withOutputEnvelope(result.outputEnvelope());
    }

    private ToolExecutionResult blocked(String toolCallId, String toolName, ToolMetadata meta,
                                        String code, String reason, FailureClass failureClass) {
        log.warn("[Gate] {} blocked: {} {}", toolName, code, reason);
        return ToolExecutionResult.of(
            toolCallId, toolName, "[" + code + "] " + reason,
            ToolExecutionStatus.BLOCKED,
            ToolError.fatal(code, reason),
            0, com.clawkit.tools.ToolOutputStats.EMPTY, null, meta, null)
            .withReliability(EffectCertainty.NOT_DISPATCHED, failureClass, null);
    }

    private ActionAttempt reportSafely(AttemptTicket ticket, EffectCertainty certainty,
                                       FailureClass failureClass, String reason) {
        try {
            return coordinator.reportOutcome(ticket, certainty, failureClass, reason);
        } catch (RuntimeException e) {
            log.error("[Gate] outcome bookkeeping failed for {}: {}", ticket.attemptId(), e.getMessage());
            return null;
        }
    }

    private ToolExecutionResult bookkeepingFailed(String toolCallId, String toolName,
                                                  ToolMetadata meta, AttemptTicket ticket,
                                                  String detail) {
        return ToolExecutionResult.of(
            toolCallId, toolName, "[T-SEG-008] " + detail,
            ToolExecutionStatus.INTERNAL_ERROR,
            ToolError.fatal("RELIABILITY_JOURNAL_FAILURE", detail),
            0, com.clawkit.tools.ToolOutputStats.EMPTY, null, meta, null)
            .withReliability(EffectCertainty.EFFECT_UNKNOWN,
                FailureClass.EXECUTION_ERROR_OUTCOME_UNKNOWN, ticket.attemptId());
    }

    private void safeCancelBeforeDispatch(AttemptTicket ticket, String reason) {
        try {
            coordinator.cancelBeforeDispatch(ticket, reason);
        } catch (RuntimeException e) {
            log.warn("[Gate] cancel bookkeeping failed for {}: {}", ticket.attemptId(), e.getMessage());
        }
    }
}
