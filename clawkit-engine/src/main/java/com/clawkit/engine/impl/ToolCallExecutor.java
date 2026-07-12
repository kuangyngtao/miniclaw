package com.clawkit.engine.impl;

import com.clawkit.engine.ApprovalHandler;
import com.clawkit.engine.ApprovalRequest;
import com.clawkit.engine.ApprovalResult;
import com.clawkit.observability.ApprovalDecidedPayload;
import com.clawkit.observability.ObservabilityRedactor;
import com.clawkit.observability.ToolCompletedPayload;
import com.clawkit.observability.ToolInvokedPayload;
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
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 工具调用统一执行器（V2）。
 *
 * <p>所有工具调用——registry 工具 + internal tools——经过此 executor，
 * 统一审批、并行/串行决策、事件分发和结果回注。
 */
public class ToolCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolCallExecutor.class);
    private static final Duration PARALLEL_DEADLINE = Duration.ofMinutes(5);

    private final Registry registry;

    public ToolCallExecutor(Registry registry) {
        this.registry = registry;
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
                ToolMetadata meta = metadataFor(call.name());
                boolean isInternal = ctx.internalTools().isInternal(call.name());
                fireToolInvoked(call, ctx, meta, isInternal);
                ToolExecutionResult r = executeOne(call, ctx);
                results.add(r);
                messages.add(toMessage(r));
                fireToolCompleted(call, ctx, r, isInternal);
            }
        }

        return new ToolExecutionBatchResult(results, messages);
    }

    // ── 核心单工具执行算法 ────────────────────────────────────────

    private ToolExecutionResult executeOne(ToolCall call, ToolExecutionContext ctx) {
        Instant start = Instant.now();

        // 1. 验证
        if (call.name() == null || call.name().isBlank()) {
            return ToolExecutionResult.invalidArguments(
                call.id(), "unknown", "Tool name is blank", 0,
                ToolMetadata.conservative("unknown"));
        }

        // 2. 取得并冻结 metadata（对所有工具，包括 internal）
        ToolMetadata meta = metadataFor(call.name());
        ToolExecutionRequest req = ToolExecutionRequest.from(call,
            new ToolExecutionScope(ctx.runId(), ctx.turnNumber(), null, null));

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
                return ToolExecutionResult.blocked(
                    call.id(), call.name(),
                    decision.reason() != null ? decision.reason() : decision.reasonCode(),
                    elapsedMs(start), meta);
            }
            case REQUIRE_APPROVAL -> {
                if (ctx.approvalHandler() == null) {
                    return ToolExecutionResult.internalError(
                        call.id(), call.name(),
                        "ASK mode requires an ApprovalHandler but none is configured",
                        elapsedMs(start), meta);
                }
                ApprovalRequest approvalReq = ApprovalRequest.from(meta, call, null);
                ApprovalResult approvalResult = ctx.approvalHandler().handle(approvalReq);
                switch (approvalResult) {
                    case ApprovalResult.Approve __ -> { /* continue */ }
                    case ApprovalResult.ApproveAllSameType a -> {
                        ctx.approvalCache().grant(call.name(), meta.riskLevel(),
                            call.arguments(), meta.sideEffects());
                    }
                    case ApprovalResult.Reject r -> {
                        fireApproval(call, ctx, "REJECT", "USER", r.reason());
                        return ToolExecutionResult.of(
                            call.id(), call.name(),
                            "User rejected: " + r.reason(),
                            ToolExecutionStatus.REJECTED,
                            com.clawkit.tools.ToolError.fatal("REJECTED", "User rejected: " + r.reason()),
                            elapsedMs(start), ToolOutputStats.EMPTY, null, meta,
                            ApprovalRecord.rejected(null, r.reason()));
                    }
                    case ApprovalResult.ModifyParams m -> {
                        fireApproval(call, ctx, "MODIFY", "USER", m.guidance());
                        return ToolExecutionResult.invalidArguments(
                            call.id(), call.name(), m.guidance(), elapsedMs(start), meta);
                    }
                }
            }
            case ALLOW -> { /* 直接执行 */ }
        }

        // 4. 执行：internal 工具走 router，注册表工具走 registry
        Instant execStart = Instant.now();
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

    private void executeParallel(List<ToolCall> calls, ToolExecutionContext ctx,
                                  List<ToolExecutionResult> results, List<Message> messages) {
        int n = calls.size();
        ToolExecutionResult[] resultArray = new ToolExecutionResult[n];
        CountDownLatch latch = new CountDownLatch(n);

        for (int i = 0; i < n; i++) {
            final int idx = i;
            final ToolCall call = calls.get(i);
            fireToolInvoked(call, ctx, metadataFor(call.name()), false);
            Thread.ofVirtual().start(() -> {
                try {
                    resultArray[idx] = executeOne(call, ctx);
                } catch (Exception e) {
                    resultArray[idx] = ToolExecutionResult.internalError(
                        call.id(), call.name(), e.getMessage(), 0,
                        metadataFor(call.name()));
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            if (!latch.await(PARALLEL_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Parallel execution deadline exceeded for {} calls", n);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (int i = 0; i < n; i++) {
            ToolExecutionResult r = resultArray[i];
            if (r != null) {
                results.add(r);
                messages.add(toMessage(r));
                fireToolCompleted(calls.get(i), ctx, r, false);
            } else {
                var missing = ToolExecutionResult.internalError(
                    calls.get(i).id(), calls.get(i).name(),
                    "Parallel execution slot missing result", 0,
                    metadataFor(calls.get(i).name()));
                results.add(missing);
                messages.add(toMessage(missing));
                fireToolCompleted(calls.get(i), ctx, missing, false);
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

    private void fireToolCompleted(ToolCall call, ToolExecutionContext ctx,
                                    ToolExecutionResult r, boolean isInternal) {
        if (ctx.recorder() == null) return;
        ToolCompletedPayload payload = new ToolCompletedPayload(
            call.id(), call.name(),
            r.success(), r.durationMs(), r.outputBytes(), r.truncated(),
            r.timedOut(), r.exitCode(),
            r.errorCode(),
            r.error() ? ObservabilityRedactor.summarizeError(r.output()) : null);
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
