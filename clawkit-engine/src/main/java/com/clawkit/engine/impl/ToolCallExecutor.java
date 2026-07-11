package com.clawkit.engine.impl;

import com.clawkit.engine.ApprovalHandler;
import com.clawkit.engine.ApprovalRequest;
import com.clawkit.engine.ApprovalResult;
import com.clawkit.engine.PermissionMode;
import com.clawkit.observability.ApprovalDecidedPayload;
import com.clawkit.observability.ObservabilityRedactor;
import com.clawkit.observability.ToolCompletedPayload;
import com.clawkit.observability.ToolInvokedPayload;
import com.clawkit.tools.Registry;
import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 工具调用统一执行器。
 * 所有工具调用——registry 工具 + internal tools——经过此 executor，
 * 统一审批、并行/串行决策、事件分发和结果回注。
 */
public class ToolCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolCallExecutor.class);

    private final Registry registry;

    public ToolCallExecutor(Registry registry) {
        this.registry = registry;
    }

    /**
     * 批量执行工具调用。
     * 只读 registry 工具并行执行；写工具和 internal tools 串行执行。
     */
    public ToolExecutionBatchResult executeBatch(List<ToolCall> calls, ToolExecutionContext ctx) {
        List<ToolExecutionResult> results = new ArrayList<>();
        List<Message> messages = new ArrayList<>();

        int n = calls.size();
        boolean allReadOnly = calls.stream().allMatch(c -> registry.isReadOnly(c.name()));
        boolean allInternal = calls.stream().allMatch(c -> ctx.internalTools().isInternal(c.name()));

        if (allInternal) {
            for (ToolCall call : calls) {
                ToolMetadata meta = metadataFor(call.name());
                fireToolInvoked(call, ctx, meta, true);
                ToolExecutionResult r = executeOne(call, ctx);
                results.add(r);
                messages.add(Message.toolResult(call.id(), r.output()));
                fireToolCompleted(call, ctx, r, true);
            }
        } else if (allReadOnly && n > 1) {
            executeParallel(calls, ctx, results, messages);
        } else {
            for (ToolCall call : calls) {
                ToolMetadata meta = metadataFor(call.name());
                fireToolInvoked(call, ctx, meta, false);
                ToolExecutionResult r = executeOne(call, ctx);
                results.add(r);
                messages.add(Message.toolResult(call.id(), r.output()));
                fireToolCompleted(call, ctx, r, false);
            }
        }

        return new ToolExecutionBatchResult(results, messages);
    }

    /** 执行单个工具调用（internal → registry） */
    private ToolExecutionResult executeOne(ToolCall call, ToolExecutionContext ctx) {
        if (ctx.internalTools().isInternal(call.name())) {
            return ctx.internalTools().execute(ToolExecutionRequest.from(call), ctx);
        }

        if (!registry.isReadOnly(call.name()) && ctx.permissionMode() == PermissionMode.PLAN) {
            return ToolExecutionResult.error(call.id(), call.name(),
                "PLAN_BLOCKED", "Tool '" + call.name()
                + "' is not available in PLAN mode.", 0,
                metadataFor(call.name()));
        }

        if (!registry.isReadOnly(call.name())
            && ctx.permissionMode() == PermissionMode.ASK
            && ctx.approvalHandler() != null
            && !ctx.autoApprovedTools().contains(call.name())) {

            ApprovalRequest req = ApprovalRequest.from(call, null);
            ApprovalResult decision = ctx.approvalHandler().handle(req);
            switch (decision) {
                case ApprovalResult.Approve __ -> { /* 执行 */ }
                case ApprovalResult.ApproveAllSameType a -> {
                    ctx.autoApprovedTools().add(a.toolName());
                }
                case ApprovalResult.Reject r -> {
                    fireApproval(call, ctx, "REJECT", "USER", r.reason());
                    return ToolExecutionResult.error(call.id(), call.name(),
                        "REJECTED", "User rejected: " + r.reason(), 0,
                        metadataFor(call.name()));
                }
                case ApprovalResult.ModifyParams m -> {
                    fireApproval(call, ctx, "MODIFY", "USER", m.guidance());
                    return ToolExecutionResult.error(call.id(), call.name(),
                        "MODIFY_PARAMS", m.guidance(), 0,
                        metadataFor(call.name()));
                }
            }
        }

        if (registry instanceof com.clawkit.tools.ToolRegistry tr) {
            return tr.execute(ToolExecutionRequest.from(call));
        }
        var toolResult = registry.execute(call);
        return new ToolExecutionResult(call.id(), call.name(),
            toolResult.output(), toolResult.isError(),
            toolResult.isError() ? "TOOL_ERROR" : null,
            0, toolResult.output().length(), false, false, null,
            ToolMetadata.conservative(call.name()));
    }

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
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (int i = 0; i < n; i++) {
            ToolExecutionResult r = resultArray[i];
            if (r != null) {
                results.add(r);
                messages.add(Message.toolResult(calls.get(i).id(), r.output()));
                fireToolCompleted(calls.get(i), ctx, r, false);
            }
        }
    }

    // ── event helpers ──────────────────────────────────────────────

    private void fireToolInvoked(ToolCall call, ToolExecutionContext ctx,
                                  ToolMetadata meta, boolean isInternal) {
        String argSummary = buildArgSummary(call);
        ToolInvokedPayload payload = new ToolInvokedPayload(
            call.id(), call.name(),
            ObservabilityRedactor.summarizeArguments(call.arguments()),
            isInternal, meta.readOnly(), meta.riskLevel(),
            meta.destructive(), meta.requiresApproval());
        ctx.recorder().record(payload, ctx.runId(), null, ctx.turnNumber(), Instant.now());
    }

    private void fireToolCompleted(ToolCall call, ToolExecutionContext ctx,
                                    ToolExecutionResult r, boolean isInternal) {
        ToolCompletedPayload payload = new ToolCompletedPayload(
            call.id(), call.name(),
            !r.error(), r.durationMs(), r.outputBytes(), r.truncated(),
            r.timedOut(), r.exitCode(),
            r.errorCode(),
            r.error() ? ObservabilityRedactor.summarizeError(r.output()) : null);
        ctx.recorder().record(payload, ctx.runId(), null, ctx.turnNumber(), Instant.now());
    }

    private void fireApproval(ToolCall call, ToolExecutionContext ctx,
                               String decision, String source, String reason) {
        ToolMetadata meta = metadataFor(call.name());
        ApprovalDecidedPayload payload = new ApprovalDecidedPayload(
            call.id(), call.name(), meta.riskLevel(), decision, source,
            reason != null ? reason : "");
        ctx.recorder().record(payload, ctx.runId(), null, ctx.turnNumber(), Instant.now());
    }

    private ToolMetadata metadataFor(String toolName) {
        if (registry instanceof com.clawkit.tools.ToolRegistry tr) {
            return tr.metadata(toolName);
        }
        return ToolMetadata.conservative(toolName);
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
