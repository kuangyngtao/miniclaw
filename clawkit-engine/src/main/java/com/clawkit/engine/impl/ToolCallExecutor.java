package com.clawkit.engine.impl;

import com.clawkit.engine.ApprovalHandler;
import com.clawkit.engine.ApprovalRequest;
import com.clawkit.engine.ApprovalResult;
import com.clawkit.engine.PermissionMode;
import com.clawkit.observability.RunEvent;
import com.clawkit.observability.model.ToolCallMetrics;
import com.clawkit.tools.Registry;
import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

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
            // internal tools 串行
            for (ToolCall call : calls) {
                fireToolInvoked(call, ctx, true);
                ToolExecutionResult r = executeOne(call, ctx);
                results.add(r);
                messages.add(Message.toolResult(call.id(), r.output()));
                fireToolCompleted(call, ctx, r, true);
            }
        } else if (allReadOnly && n > 1) {
            // 只读 registry 工具并行
            executeParallel(calls, ctx, results, messages);
        } else {
            // 混合或单个工具串行
            for (ToolCall call : calls) {
                fireToolInvoked(call, ctx, false);
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
        // internal tools
        if (ctx.internalTools().isInternal(call.name())) {
            return ctx.internalTools().execute(ToolExecutionRequest.from(call), ctx);
        }

        // PLAN 模式拦截写工具
        if (!registry.isReadOnly(call.name()) && ctx.permissionMode() == PermissionMode.PLAN) {
            return ToolExecutionResult.error(call.id(), call.name(),
                "PLAN_BLOCKED", "Tool '" + call.name()
                + "' is not available in PLAN mode.", 0,
                registry instanceof com.clawkit.tools.ToolRegistry tr
                    ? tr.metadata(call.name())
                    : com.clawkit.tools.ToolMetadata.conservative(call.name()));
        }

        // ASK 模式审批
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
                    ctx.eventSink().accept(new RunEvent.ApprovalDecision(
                        ctx.runId(), ctx.turnNumber(), call.name(), "REJECT", r.reason()));
                    return ToolExecutionResult.error(call.id(), call.name(),
                        "REJECTED", "User rejected: " + r.reason(), 0,
                        registry instanceof com.clawkit.tools.ToolRegistry tr
                            ? tr.metadata(call.name())
                            : com.clawkit.tools.ToolMetadata.conservative(call.name()));
                }
                case ApprovalResult.ModifyParams m -> {
                    ctx.eventSink().accept(new RunEvent.ApprovalDecision(
                        ctx.runId(), ctx.turnNumber(), call.name(), "MODIFY", m.guidance()));
                    return ToolExecutionResult.error(call.id(), call.name(),
                        "MODIFY_PARAMS", m.guidance(), 0,
                        registry instanceof com.clawkit.tools.ToolRegistry tr
                            ? tr.metadata(call.name())
                            : com.clawkit.tools.ToolMetadata.conservative(call.name()));
                }
            }
        }

        // registry 执行
        if (registry instanceof com.clawkit.tools.ToolRegistry tr) {
            return tr.execute(ToolExecutionRequest.from(call));
        }
        // fallback: 旧 Registry 接口
        var toolResult = registry.execute(call);
        return new ToolExecutionResult(call.id(), call.name(),
            toolResult.output(), toolResult.isError(),
            toolResult.isError() ? "TOOL_ERROR" : null,
            0, toolResult.output().length(), false, false, null,
            com.clawkit.tools.ToolMetadata.conservative(call.name()));
    }

    /** 并行执行只读工具 */
    private void executeParallel(List<ToolCall> calls, ToolExecutionContext ctx,
                                  List<ToolExecutionResult> results, List<Message> messages) {
        int n = calls.size();
        ToolExecutionResult[] resultArray = new ToolExecutionResult[n];
        CountDownLatch latch = new CountDownLatch(n);

        for (int i = 0; i < n; i++) {
            final int idx = i;
            final ToolCall call = calls.get(i);
            fireToolInvoked(call, ctx, false);
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

    private void fireToolInvoked(ToolCall call, ToolExecutionContext ctx, boolean isInternal) {
        String argSummary = buildArgSummary(call);
        ctx.eventSink().accept(new RunEvent.ToolInvoked(
            ctx.runId(), ctx.turnNumber(), call.id(), call.name(),
            argSummary, isInternal || registry.isReadOnly(call.name())));
    }

    private void fireToolCompleted(ToolCall call, ToolExecutionContext ctx,
                                    ToolExecutionResult r, boolean isInternal) {
        String argSummary = buildArgSummary(call);
        ctx.eventSink().accept(new RunEvent.ToolCompleted(ctx.runId(), ctx.turnNumber(),
            new ToolCallMetrics(ctx.runId(), ctx.turnNumber(),
                call.id(), call.name(), argSummary,
                registry.isReadOnly(call.name()), isInternal, false, null,
                !r.error(), r.durationMs(), r.outputBytes(), r.truncated(),
                r.errorCode(), r.error() ? r.output() : null)));
    }

    private static String buildArgSummary(ToolCall call) {
        var args = call.arguments();
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
