package com.clawkit.engine.impl;

import com.clawkit.engine.ApprovalHandler;
import com.clawkit.engine.ApprovalRequest;
import com.clawkit.engine.ApprovalResult;
import com.clawkit.observability.ApprovalDecidedPayload;
import com.clawkit.observability.ObservabilityRedactor;
import com.clawkit.observability.ToolCompletedPayload;
import com.clawkit.observability.ToolInvokedPayload;
import com.clawkit.observability.ToolRetryScheduledPayload;
import com.clawkit.reliability.DefaultToolRetryPolicy;
import com.clawkit.reliability.RetrySleeper;
import com.clawkit.reliability.ToolRetryContext;
import com.clawkit.reliability.ToolRetryPolicy;
import com.clawkit.tools.ApprovalGrantCache;
import com.clawkit.tools.ApprovalRecord;
import com.clawkit.tools.PermissionMode;
import com.clawkit.tools.PermissionPolicy;
import com.clawkit.tools.Registry;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolExecutionScope;
import com.clawkit.tools.ToolExecutionStatus;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolOutputStats;
import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.action.RecoveryDirective;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 工具调用统一执行器（V2）。
 *
 * <p>所有工具调用——registry 工具 + internal tools——经过此 executor，
 * 统一审批、并行/串行决策、事件分发和结果回注。
 *
 * <p>P1-G4：executor 同时是唯一 Side Effect Gate 入口——
 * 副作用工具（readOnly=false 或声明 side effect）必须提供 ActionDescriptor
 * 并通过 Attempt 生命周期执行；无法生成描述符时 fail closed。
 */
public class ToolCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolCallExecutor.class);
    private static final Duration PARALLEL_DEADLINE = Duration.ofMinutes(5);
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final Registry registry;
    private final com.clawkit.reliability.gate.SideEffectGate sideEffectGate;
    private final ToolRetryPolicy retryPolicy;
    private final RetrySleeper retrySleeper;

    public ToolCallExecutor(Registry registry) {
        this(registry, null);
    }

    public ToolCallExecutor(Registry registry,
                            com.clawkit.reliability.gate.SideEffectGate sideEffectGate) {
        this(registry, sideEffectGate, new DefaultToolRetryPolicy(), RetrySleeper.SYSTEM);
    }

    /** 完整构造器（测试注入 retryPolicy/sleeper） */
    public ToolCallExecutor(Registry registry,
                            com.clawkit.reliability.gate.SideEffectGate sideEffectGate,
                            ToolRetryPolicy retryPolicy,
                            RetrySleeper retrySleeper) {
        this.registry = registry;
        this.sideEffectGate = sideEffectGate;
        this.retryPolicy = retryPolicy;
        this.retrySleeper = retrySleeper;
    }

    /**
     * 批量执行工具调用。
     */
    public ToolExecutionBatchResult executeBatch(List<ToolCall> calls, ToolExecutionContext ctx) {
        List<ToolExecutionResult> results = new ArrayList<>();
        List<Message> messages = new ArrayList<>();

        int n = calls.size();
        boolean allInternal = calls.stream().allMatch(c -> ctx.internalTools().isInternal(c.name()));
        // 并发策略：internal 或 并行安全 的工具可并行
        boolean canParallel = !allInternal
            && calls.size() > 1
            && calls.stream().allMatch(c -> {
                var meta = metadataFor(c.name());
                return meta.readOnly()
                    && meta.executionPolicy().concurrency() != com.clawkit.tools.ToolExecutionPolicy.ToolConcurrency.SERIAL;
            });

        if (canParallel) {
            executeParallel(calls, ctx, results, messages);
        } else {
            for (ToolCall call : calls) {
                ToolMetadata meta = metadataFor(call.name(), ctx);
                boolean isInternal = ctx.internalTools().isInternal(call.name());
                fireToolInvoked(call, ctx, meta, isInternal);
                ExecutedToolCall executed;
                if (ctx.control().isCancelled()) {
                    var r = ToolExecutionResult.cancelled(call.id(), call.name(),
                        "执行已被取消，工具未启动。", 0, meta, true);
                    executed = new ExecutedToolCall(r, 1, 0, "CANCELLED");
                } else {
                    executed = executeOne(call, ctx);
                }
                results.add(executed.result());
                messages.add(toMessage(executed.result()));
                fireToolCompleted(call, ctx, executed, isInternal);
            }
        }

        return new ToolExecutionBatchResult(results, messages);
    }

    // ── 核心单工具执行算法 ────────────────────────────────────────

    /** P0-2：将单次无重试结果包装为 ExecutedToolCall */
    private static ExecutedToolCall singleAttempt(ToolExecutionResult r) {
        return new ExecutedToolCall(r, 1, r.durationMs(), r.success() ? "SUCCESS" : "FIRST_ATTEMPT_FAILED");
    }

    private ExecutedToolCall executeOne(ToolCall call, ToolExecutionContext ctx) {
        Instant start = Instant.now();

        // 1. 验证
        if (call.name() == null || call.name().isBlank()) {
            return singleAttempt(ToolExecutionResult.invalidArguments(
                call.id(), "unknown", "Tool name is blank", 0,
                ToolMetadata.conservative("unknown")));
        }

        // 2. 取得并冻结 metadata（对所有工具，包括 internal）
        ToolMetadata meta = metadataFor(call.name(), ctx);
        ToolExecutionRequest req = ToolExecutionRequest.from(call,
            new ToolExecutionScope(ctx.runId(), ctx.turnNumber(), null, null, ctx.control()));
        boolean sideEffect = !meta.isReadOnly() || !meta.sideEffects().isEmpty();
        com.clawkit.tools.action.ActionDescriptor descriptor =
            sideEffect ? describeAction(call, req, ctx) : null;
        if (sideEffect && descriptor == null) {
            return singleAttempt(ToolExecutionResult.blocked(
                call.id(), call.name(),
                "[T-SEG-001] Side-effecting tool has no trusted ActionDescriptor.",
                elapsedMs(start), meta));
        }

        // 3. 权限评估（internal 工具也经过此步骤）
        PermissionMode mode = ctx.permissionMode() != null ? ctx.permissionMode() : PermissionMode.ASK;
        PermissionPolicy.PermissionDecision decision;
        if (ctx.permissionPolicy() != null) {
            decision = ctx.permissionPolicy().evaluate(mode, meta, req, ctx.approvalCache());
        } else {
            decision = defaultPermission(mode, meta, call, ctx);
        }

        switch (decision.outcome()) {
            case DENY -> {
                return singleAttempt(ToolExecutionResult.blocked(
                    call.id(), call.name(),
                    decision.reason() != null ? decision.reason() : decision.reasonCode(),
                    elapsedMs(start), meta));
            }
            case REQUIRE_APPROVAL -> {
                if (ctx.approvalHandler() == null) {
                    return singleAttempt(ToolExecutionResult.internalError(
                        call.id(), call.name(),
                        "ASK mode requires an ApprovalHandler but none is configured",
                        elapsedMs(start), meta));
                }
                ApprovalRequest approvalReq = ApprovalRequest.from(
                    meta, call, null, descriptor != null ? descriptor.fingerprint() : null);
                ApprovalResult approvalResult = ctx.approvalHandler().handle(approvalReq);
                switch (approvalResult) {
                    case ApprovalResult.Approve __ -> { /* continue */ }
                    case ApprovalResult.ApproveAllSameType a -> {
                        ctx.approvalCache().grant(call.name(), meta.riskLevel(),
                            call.arguments(), meta.sideEffects());
                    }
                    case ApprovalResult.Reject r -> {
                        fireApproval(call, ctx, "REJECT", "USER", r.reason());
                        return singleAttempt(ToolExecutionResult.of(
                            call.id(), call.name(),
                            "User rejected: " + r.reason(),
                            ToolExecutionStatus.REJECTED,
                            com.clawkit.tools.ToolError.fatal("REJECTED", "User rejected: " + r.reason()),
                            elapsedMs(start), ToolOutputStats.EMPTY, null, meta,
                            ApprovalRecord.rejected(null, r.reason())));
                    }
                    case ApprovalResult.ModifyParams m -> {
                        fireApproval(call, ctx, "MODIFY", "USER", m.guidance());
                        return singleAttempt(ToolExecutionResult.invalidArguments(
                            call.id(), call.name(), m.guidance(), elapsedMs(start), meta));
                    }
                }
            }
            case ALLOW -> { /* 直接执行 */ }
        }

        // 4. 执行：P1-G4 副作用工具必须携带 ActionDescriptor 走 Side Effect Gate
        // P1-A2：只读可信工具走有界重试 loop
        try {
            ctx.control().acquireToolCall();
        } catch (com.clawkit.tools.control.ExecutionHaltedException halted) {
            return singleAttempt(ToolExecutionResult.halted(
                call.id(), call.name(), halted.reason(),
                elapsedMs(start), meta));
        }

        Instant execStart = Instant.now();
        if (sideEffect) {
            if (sideEffectGate == null) {
                return singleAttempt(ToolExecutionResult.blocked(
                    call.id(), call.name(),
                    "[T-SEG-000] Side Effect Gate 未装配，副作用工具按 fail-closed 拒绝执行。",
                    elapsedMs(start), meta));
            }
            return singleAttempt(sideEffectGate.execute(descriptor, call.id(), call.name(), meta,
                ctx.control(), ctx.runId(),
                () -> doExecute(call, req, ctx, meta, execStart)));
        }

        // P1-A2：只读 + 可信 → 有界重试
        boolean eligibleForRetry = meta.isReadOnly()
            && (meta.sideEffects() == null || meta.sideEffects().isEmpty())
            && meta.provenance() != null && meta.provenance().trusted();
        if (eligibleForRetry) {
            return executeReadOnlyWithRetry(call, req, ctx, meta, execStart);
        }
        return singleAttempt(doExecute(call, req, ctx, meta, execStart));
    }

    /**
     * 只读工具有界重试 loop（P1-A2）。
     *
     * <p>条件：readOnly + 无副作用 + trusted provenance。
     * metadata 在 loop 前冻结；每次新增 attempt 前再次 acquireToolCall()。
     * 对模型只回注一个最终 tool result。
     */
    private ExecutedToolCall executeReadOnlyWithRetry(
            ToolCall call, ToolExecutionRequest req, ToolExecutionContext ctx,
            ToolMetadata meta, Instant execStart) {

        int attempt = 0;
        int maxAttempts = (retryPolicy instanceof DefaultToolRetryPolicy dp)
            ? dp.maxAttempts() : DEFAULT_MAX_ATTEMPTS;
        ToolExecutionResult lastResult = null;
        String stopReason = null;
        Instant logicalStart = Instant.now();

        while (attempt < maxAttempts) {
            attempt++;

            // 每次新增 attempt 前再次 acquire（首次已在 executeOne 中完成）
            if (attempt > 1) {
                try {
                    ctx.control().acquireToolCall();
                } catch (com.clawkit.tools.control.ExecutionHaltedException halted) {
                    lastResult = ToolExecutionResult.halted(
                        call.id(), call.name(), halted.reason(),
                        elapsedMs(execStart), meta);
                    stopReason = "CONTROL_HALTED";
                    break;
                }
            }

            // 真实执行
            lastResult = doExecute(call, req, ctx, meta, execStart);

            // 成功 → 立即返回
            if (lastResult.success()) {
                stopReason = "SUCCESS";
                break;
            }

            // 决策是否重试
            var retryCtx = new ToolRetryContext(
                lastResult, meta, attempt, maxAttempts,
                Duration.between(logicalStart, Instant.now()),
                ctx.control().remainingTime().orElse(null));
            var decision = retryPolicy.decide(retryCtx);

            if (!decision.retrySameInput()) {
                stopReason = decision.reasonCode();
                break;
            }

            // 发重试调度事件
            fireToolRetryScheduled(call, ctx, attempt, maxAttempts,
                decision.delay().toMillis(),
                lastResult.failureClass() != null ? lastResult.failureClass().name() : null,
                decision.reasonCode());

            // 退避等待 + control checkpoint
            try {
                ctx.control().checkpoint();
                retrySleeper.sleep(decision.delay());
                ctx.control().checkpoint();
            } catch (com.clawkit.tools.control.ExecutionHaltedException halted) {
                lastResult = ToolExecutionResult.halted(
                    call.id(), call.name(), halted.reason(),
                    elapsedMs(execStart), meta);
                stopReason = "CONTROL_HALTED_DURING_WAIT";
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastResult = ToolExecutionResult.cancelled(
                    call.id(), call.name(), "Retry wait interrupted",
                    elapsedMs(execStart), meta, true);
                stopReason = "INTERRUPTED";
                break;
            }
        }

        long logicalDuration = Duration.between(logicalStart, Instant.now()).toMillis();

        // 在 final result 的 error details 中记录重试信息
        if (attempt > 1 && lastResult != null && lastResult.toolError() != null) {
            var details = new java.util.HashMap<>(lastResult.toolError().details());
            details.put("attemptsMade", String.valueOf(attempt));
            if (stopReason != null) details.put("finalStopReason", stopReason);
            lastResult = new ToolExecutionResult(
                lastResult.toolCallId(), lastResult.toolName(), lastResult.output(),
                lastResult.error(), lastResult.errorCode(), logicalDuration,
                lastResult.outputBytes(), lastResult.truncated(), lastResult.timedOut(),
                lastResult.exitCode(), lastResult.metadata(),
                lastResult.status(),
                new com.clawkit.tools.ToolError(
                    lastResult.toolError().code(),
                    lastResult.toolError().message(),
                    lastResult.toolError().retryable(),
                    java.util.Map.copyOf(details)),
                lastResult.outputStats(), lastResult.approval(), lastResult.auditId(),
                lastResult.effectCertainty(), lastResult.failureClass(),
                lastResult.outputEnvelope(), lastResult.attemptId());
        }

        return new ExecutedToolCall(lastResult, attempt, logicalDuration, stopReason);
    }

    /** 真实执行体：internal 工具走 router，注册表工具走 registry。 */
    private ToolExecutionResult doExecute(ToolCall call, ToolExecutionRequest req,
                                          ToolExecutionContext ctx, ToolMetadata meta,
                                          Instant execStart) {
        ToolExecutionResult result;
        try {
            if (ctx.internalTools().isInternal(call.name())) {
                result = ctx.internalTools().execute(req, ctx);
            } else if (registry instanceof com.clawkit.tools.ToolRegistry tr) {
                result = tr.execute(req);
            } else {
                var toolResult = registry.execute(call);
                // 修复：isError()=true 表示错误，应该传入 true
                result = new ToolExecutionResult(call.id(), call.name(),
                    toolResult.output(), toolResult.isError(),
                    toolResult.isError() ? "TOOL_ERROR" : null,
                    elapsedMs(execStart), toolResult.output().length(),
                    false, false, null,
                    ToolMetadata.conservative(call.name()));
            }
        } catch (Exception e) {
            result = ToolExecutionResult.internalError(
                call.id(), call.name(), e.getMessage(), elapsedMs(execStart), meta);
        }
        return result;
    }

    /** 生成 ActionDescriptor：internal 走 router，registry 工具走 Tool.describeAction()。 */
    private com.clawkit.tools.action.ActionDescriptor describeAction(
            ToolCall call, ToolExecutionRequest req, ToolExecutionContext ctx) {
        try {
            if (ctx.internalTools().isInternal(call.name())) {
                return ctx.internalTools().describe(req);
            }
            return registry.lookup(call.name())
                .map(tool -> tool.describeAction(req))
                .orElse(null);
        } catch (Exception e) {
            log.warn("describeAction failed for {}: {}", call.name(), e.getMessage());
            return null; // fail closed
        }
    }

    // ── 降级权限逻辑 ──────────────────────────────────────────────

    private PermissionPolicy.PermissionDecision defaultPermission(
        PermissionMode mode, ToolMetadata meta, ToolCall call, ToolExecutionContext ctx) {
        return switch (mode) {
            case PLAN -> meta.isReadOnly()
                ? PermissionPolicy.PermissionDecision.allow()
                : PermissionPolicy.PermissionDecision.deny("PLAN_BLOCKED",
                    "Tool '" + call.name() + "' is not available in PLAN mode.");

            case ASK -> {
                if (meta.isReadOnly() && !meta.isApprovalRequired()) {
                    yield PermissionPolicy.PermissionDecision.allow();
                }
                if (ctx.approvalCache().isGranted(call.name(), meta.riskLevel(),
                    call.arguments(), meta.sideEffects())) {
                    yield PermissionPolicy.PermissionDecision.allow();
                }
                yield PermissionPolicy.PermissionDecision.requireApproval(
                    "Write tool or approval-required tool in ASK mode");
            }

            case AUTO -> {
                if (meta.isApprovalRequired()) {
                    yield PermissionPolicy.PermissionDecision.requireApproval(
                        "Tool requires approval even in AUTO mode");
                }
                yield PermissionPolicy.PermissionDecision.allow();
            }
        };
    }

    // ── 并行执行 ─────────────────────────────────────────────────

    /**
     * P1-G1：并行任务由 task group 持有真实 Future 句柄。
     * 取消时不再 fork 新任务，取消未完成任务（中断线程）并等待终态归并；
     * 未完成槽位以 CANCELLED 终态回注，不再留下 "missing result"。
     */
    private void executeParallel(List<ToolCall> calls, ToolExecutionContext ctx,
                                  List<ToolExecutionResult> results, List<Message> messages) {
        int n = calls.size();
        List<java.util.concurrent.Future<ExecutedToolCall>> futures = new ArrayList<>(n);

        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int i = 0; i < n; i++) {
                final ToolCall call = calls.get(i);
                fireToolInvoked(call, ctx, metadataFor(call.name()), false);
                if (ctx.control().isCancelled()) {
                    futures.add(java.util.concurrent.CompletableFuture.completedFuture(
                        singleAttempt(ToolExecutionResult.cancelled(call.id(), call.name(),
                            "执行已被取消，工具未启动。", 0, metadataFor(call.name()), true))));
                    continue;
                }
                futures.add(executor.submit(() -> executeOne(call, ctx)));
            }

            try (var cancelReg = ctx.control().onCancel(() ->
                    futures.forEach(f -> f.cancel(true)))) {
                long deadlineNanos = System.nanoTime() + PARALLEL_DEADLINE.toNanos();
                for (int i = 0; i < n; i++) {
                    ToolCall call = calls.get(i);
                    ExecutedToolCall executed;
                    try {
                        long remaining = Math.max(1, deadlineNanos - System.nanoTime());
                        executed = futures.get(i).get(remaining, TimeUnit.NANOSECONDS);
                    } catch (java.util.concurrent.CancellationException e) {
                        executed = singleAttempt(ToolExecutionResult.cancelled(call.id(), call.name(),
                            "并行执行已被取消。", 0, metadataFor(call.name()), false));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        executed = singleAttempt(ToolExecutionResult.cancelled(call.id(), call.name(),
                            "并行执行等待被中断。", 0, metadataFor(call.name()), false));
                    } catch (java.util.concurrent.TimeoutException e) {
                        futures.get(i).cancel(true);
                        log.warn("Parallel execution deadline exceeded for tool {}", call.name());
                        executed = singleAttempt(ToolExecutionResult.timedOut(call.id(), call.name(),
                            "并行执行超过 " + PARALLEL_DEADLINE.toMinutes() + " 分钟上限。",
                            PARALLEL_DEADLINE.toMillis(), ToolOutputStats.EMPTY,
                            metadataFor(call.name())));
                    } catch (java.util.concurrent.ExecutionException e) {
                        executed = singleAttempt(ToolExecutionResult.internalError(call.id(), call.name(),
                            e.getCause() != null ? e.getCause().getMessage() : e.getMessage(),
                            0, metadataFor(call.name())));
                    }
                    results.add(executed.result());
                    messages.add(toMessage(executed.result()));
                    fireToolCompleted(call, ctx, executed, false);
                }
            }
        } finally {
            futures.forEach(f -> {
                if (!f.isDone()) {
                    f.cancel(true);
                }
            });
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── 元数据查询 ────────────────────────────────────────────────

    private boolean isReadOnly(String name) {
        return registry.isReadOnly(name);
    }

    /** 通过 Registry.lookup() 获取 metadata，isReadOnly() 作为回退 */
    ToolMetadata metadataFor(String toolName) {
        var toolOpt = registry.lookup(toolName);
        if (toolOpt.isPresent()) {
            return toolOpt.get().metadata();
        }
        // 回退：使用 registry.isReadOnly() 推断基本 metadata
        boolean ro = registry.isReadOnly(toolName);
        return new ToolMetadata(
            toolName, "", null, null,
            new com.clawkit.tools.ToolBehavior(
                ro, ro ? com.clawkit.tools.ToolRiskLevel.LOW : com.clawkit.tools.ToolRiskLevel.MEDIUM,
                !ro, false, !ro, false, java.util.Set.of()),
            ro ? com.clawkit.tools.ToolExecutionPolicy.readOnlyDefaults()
               : com.clawkit.tools.ToolExecutionPolicy.defaults(),
            com.clawkit.tools.ToolMetadataProvenance.conservativeDefault());
    }

    /** internal tool 优先取 router 注册的权威 metadata。 */
    ToolMetadata metadataFor(String toolName, ToolExecutionContext ctx) {
        if (ctx != null && ctx.internalTools() != null && ctx.internalTools().isInternal(toolName)) {
            return ctx.internalTools().metadataOf(toolName)
                .orElseGet(() -> metadataFor(toolName));
        }
        return metadataFor(toolName);
    }

    private Message toMessage(ToolExecutionResult r) {
        return Message.toolResult(r.toolCallId(), r.output());
    }

    private long elapsedMs(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
    }

    // ── event helpers ──────────────────────────────────────────────

    private void fireToolInvoked(ToolCall call, ToolExecutionContext ctx,
                                  ToolMetadata meta, boolean isInternal) {
        if (ctx.recorder() == null) return; // no-op recorder for isolated contexts
        ToolInvokedPayload payload = new ToolInvokedPayload(
            call.id(), call.name(),
            ObservabilityRedactor.summarizeArguments(call.arguments()),
            isInternal, meta.readOnly(), meta.riskLevel(),
            meta.destructive(), meta.requiresApproval());
        ctx.recorder().record(payload, ctx.runId(), null, ctx.turnNumber(), Instant.now());
    }

    /** P0-2：从 ExecutedToolCall 提取真实 attemptCount/logicalDuration，写入 event */
    private void fireToolCompleted(ToolCall call, ToolExecutionContext ctx,
                                    ExecutedToolCall executed, boolean isInternal) {
        if (ctx.recorder() == null) return;
        var r = executed.result();
        var stats = r.outputStats();
        ToolCompletedPayload payload = new ToolCompletedPayload(
            call.id(), call.name(),
            r.success(), executed.logicalDurationMs(), r.outputBytes(), r.truncated(),
            r.timedOut(), r.exitCode(),
            r.errorCode(),
            r.error() ? ObservabilityRedactor.summarizeError(r.output()) : null,
            executed.attemptCount(),
            r.failureClass() != null ? r.failureClass().name() : null,
            com.clawkit.reliability.FailureDecisionTable.directiveFor(
                r.failureClass() != null ? r.failureClass() : com.clawkit.tools.action.FailureClass.UNCLASSIFIED).name(),
            executed.finalStopReason(),
            stats != null ? stats.totalBytes() : r.outputBytes(),
            stats != null ? stats.retainedSourceBytes() : r.outputBytes(),
            stats != null ? stats.returnedBytes() : r.outputBytes(),
            stats != null ? stats.totalLines() : -1,
            stats != null ? stats.returnedLines() : -1,
            stats != null ? stats.truncationReason() : null,
            stats != null ? stats.retentionPolicy() : "LEGACY_V0",
            stats != null ? stats.inputComplete() : true);
        ctx.recorder().record(payload, ctx.runId(), null, ctx.turnNumber(), Instant.now());
    }

    private void fireToolCompleted(ToolCall call, ToolExecutionContext ctx,
                                    ToolExecutionResult r, boolean isInternal) {
        fireToolCompleted(call, ctx, r, isInternal, 1, null);
    }

    private void fireToolCompleted(ToolCall call, ToolExecutionContext ctx,
                                    ToolExecutionResult r, boolean isInternal,
                                    int attemptCount, String finalStopReason) {
        if (ctx.recorder() == null) return;
        var stats = r.outputStats();
        ToolCompletedPayload payload = new ToolCompletedPayload(
            call.id(), call.name(),
            r.success(), r.durationMs(), r.outputBytes(), r.truncated(),
            r.timedOut(), r.exitCode(),
            r.errorCode(),
            r.error() ? ObservabilityRedactor.summarizeError(r.output()) : null,
            attemptCount,
            r.failureClass() != null ? r.failureClass().name() : null,
            null,
            finalStopReason,
            stats != null ? stats.totalBytes() : r.outputBytes(),
            stats != null ? stats.retainedSourceBytes() : r.outputBytes(),
            stats != null ? stats.returnedBytes() : r.outputBytes(),
            stats != null ? stats.totalLines() : -1,
            stats != null ? stats.returnedLines() : -1,
            stats != null ? stats.truncationReason() : null,
            stats != null ? stats.retentionPolicy() : "LEGACY_V0",
            stats != null ? stats.inputComplete() : true);
        ctx.recorder().record(payload, ctx.runId(), null, ctx.turnNumber(), Instant.now());
    }

    private void fireToolRetryScheduled(ToolCall call, ToolExecutionContext ctx,
                                         int attemptNumber, int maxAttempts,
                                         long delayMs, String failureClass,
                                         String retryReason) {
        if (ctx.recorder() == null) return;
        ToolRetryScheduledPayload payload = new ToolRetryScheduledPayload(
            call.id(), call.name(), attemptNumber, maxAttempts,
            delayMs, failureClass, retryReason);
        ctx.recorder().record(payload, ctx.runId(), null, ctx.turnNumber(), Instant.now());
    }

    private void fireApproval(ToolCall call, ToolExecutionContext ctx,
                               String decision, String source, String reason) {
        if (ctx.recorder() == null) return;
        ToolMetadata meta = metadataFor(call.name());
        ApprovalDecidedPayload payload = new ApprovalDecidedPayload(
            call.id(), call.name(), meta.riskLevel(), decision, source,
            reason != null ? reason : "");
        ctx.recorder().record(payload, ctx.runId(), null, ctx.turnNumber(), Instant.now());
    }

    static String buildArgSummary(ToolCall call) {
        JsonNode args = call.arguments();
        if (args == null) return "";
        for (String key : List.of("path", "file_path", "pattern", "command", "query", "url")) {
            if (args.has(key)) {
                String val = args.get(key).asText();
                return key + "=" + (val.length() > 60 ? val.substring(0, 57) + "..." : val);
            }
        }
        var it = args.fields();
        if (it.hasNext()) {
            var e = it.next();
            String val = e.getValue().asText();
            return e.getKey() + "=" + (val.length() > 60 ? val.substring(0, 57) + "..." : val);
        }
        return "";
    }
}
