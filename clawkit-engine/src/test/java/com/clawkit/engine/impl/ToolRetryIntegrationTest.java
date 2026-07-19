package com.clawkit.engine.impl;

import com.clawkit.observability.RunRecorder;
import com.clawkit.observability.RunEventPayload;
import com.clawkit.observability.ToolCompletedPayload;
import com.clawkit.observability.ToolRetryScheduledPayload;
import com.clawkit.reliability.CancellationTree;
import com.clawkit.reliability.DefaultToolRetryPolicy;
import com.clawkit.reliability.RetrySleeper;
import com.clawkit.reliability.WorkBudgetLedger;
import com.clawkit.tools.*;
import com.clawkit.tools.control.TokenBudget;
import com.clawkit.tools.schema.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** P1-A2：只读工具 retry loop 集成测试。 */
class ToolRetryIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 可控制失败次数的只读探针工具 */
    static class FailingReadTool implements Tool {
        final String name;
        final AtomicInteger callCount = new AtomicInteger(0);
        volatile int failTimes = 0; // 前 N 次失败

        FailingReadTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "test"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(name, "test", null, null,
                new ToolBehavior(true, ToolRiskLevel.LOW, false, true, false, false, Set.of()),
                ToolExecutionPolicy.readOnlyDefaults(),
                ToolMetadataProvenance.builtin(name));
        }

        @Override
        public Result<String> execute(String arguments) {
            int n = callCount.incrementAndGet();
            if (n <= failTimes) {
                return new Result.Err<>(new Result.ErrorInfo("IO_ERROR", "transient error " + n));
            }
            return new Result.Ok<>("ok");
        }

        /** V2 override：返回 retryable error 以触发 PA-1 retry loop */
        @Override
        public ToolExecutionResult execute(ToolExecutionRequest req) {
            int n = callCount.incrementAndGet();
            if (n <= failTimes) {
                return new ToolExecutionResult(
                    req.toolCallId(), name, "transient error " + n,
                    ToolExecutionStatus.TOOL_ERROR,
                    ToolError.retryable("IO_ERROR", "transient error " + n),
                    1, ToolOutputStats.fromOutput("transient error " + n, false),
                    null, metadata(), null, java.util.UUID.randomUUID().toString())
                    .withReliability(
                        com.clawkit.tools.action.EffectCertainty.NO_EFFECT_CONFIRMED,
                        com.clawkit.tools.action.FailureClass.LOCAL_ERROR_NO_EFFECT,
                        java.util.UUID.randomUUID().toString());
            }
            return ToolExecutionResult.success(
                req.toolCallId(), name, "ok", 1, metadata());
        }
    }

    private ToolRegistry registryWith(Tool tool) {
        ToolRegistry tr = new ToolRegistry();
        tr.register(tool);
        return tr;
    }

    private ToolExecutionContext ctx(CancellationTree control, List<RunEventPayload> events) {
        return new ToolExecutionContext(
            "run-1", 1,
            PermissionMode.AUTO,
            new DefaultPermissionPolicy(),
            null, // approvalHandler
            new RunRecorder() {
                @Override public void record(RunEventPayload payload, String runId,
                                              String parentRunId, Integer turn, Instant at) {
                    events.add(payload);
                }
            },
            new InternalToolRouter(),
            ApprovalGrantCache.noop(),
            control);
    }

    // ── 0 次重试：首次成功 ──────────────────────────────────────────

    @Test
    void firstAttemptSucceedsNoRetry() {
        var tool = new FailingReadTool("read_ok");
        tool.failTimes = 0;
        var events = new CopyOnWriteArrayList<RunEventPayload>();
        var control = CancellationTree.root(null,
            TokenBudget.unlimited(), WorkBudgetLedger.of(Long.MAX_VALUE, Long.MAX_VALUE));
        var executor = new ToolCallExecutor(registryWith(tool), null,
            new DefaultToolRetryPolicy(new Random(1), 3), RetrySleeper.noop());

        var batch = executor.executeBatch(
            List.of(tc("c1", "read_ok")), ctx(control, events));

        assertThat(batch.results().get(0).success()).isTrue();
        assertThat(tool.callCount.get()).isEqualTo(1);
    }

    // ── 失败两次后第三次成功 ────────────────────────────────────────

    @Test
    void failTwiceSucceedThird() {
        var tool = new FailingReadTool("read_retry");
        tool.failTimes = 2;
        var events = new CopyOnWriteArrayList<RunEventPayload>();
        var control = CancellationTree.root(null,
            TokenBudget.unlimited(), WorkBudgetLedger.of(Long.MAX_VALUE, Long.MAX_VALUE));
        var executor = new ToolCallExecutor(registryWith(tool), null,
            new DefaultToolRetryPolicy(new Random(1), 3), RetrySleeper.noop());

        var batch = executor.executeBatch(
            List.of(tc("c1", "read_retry")), ctx(control, events));

        assertThat(batch.results().get(0).success()).isTrue();
        assertThat(tool.callCount.get()).isEqualTo(3);
        assertThat(batch.results().size()).isEqualTo(1); // 只回注一个结果

        // ToolRetryScheduled 事件
        var scheduled = events.stream()
            .filter(e -> e instanceof ToolRetryScheduledPayload).count();
        assertThat(scheduled).isEqualTo(2);
    }

    // ── 三次全部失败 ────────────────────────────────────────────────

    @Test
    void allThreeAttemptsFail() {
        var tool = new FailingReadTool("read_fail");
        tool.failTimes = 10;
        var events = new CopyOnWriteArrayList<RunEventPayload>();
        var control = CancellationTree.root(null,
            TokenBudget.unlimited(), WorkBudgetLedger.of(Long.MAX_VALUE, Long.MAX_VALUE));
        var executor = new ToolCallExecutor(registryWith(tool), null,
            new DefaultToolRetryPolicy(new Random(1), 3), RetrySleeper.noop());

        var batch = executor.executeBatch(
            List.of(tc("c1", "read_fail")), ctx(control, events));

        assertThat(batch.results().get(0).success()).isFalse();
        assertThat(tool.callCount.get()).isEqualTo(3);
        assertThat(batch.results().size()).isEqualTo(1);
    }

    // ── 退出期间取消 ────────────────────────────────────────────────

    @Test
    void cancelPreventsRetry() {
        var tool = new FailingReadTool("read_cancel");
        tool.failTimes = 10;
        var events = new CopyOnWriteArrayList<RunEventPayload>();
        var control = CancellationTree.root(null,
            TokenBudget.unlimited(), WorkBudgetLedger.of(Long.MAX_VALUE, Long.MAX_VALUE));
        var executor = new ToolCallExecutor(registryWith(tool), null,
            new DefaultToolRetryPolicy(new Random(1), 3), RetrySleeper.noop());

        control.cancel(); // 执行前取消

        var batch = executor.executeBatch(
            List.of(tc("c1", "read_cancel")), ctx(control, events));

        assertThat(batch.results().get(0).success()).isFalse();
        assertThat(tool.callCount.get()).isLessThanOrEqualTo(1);
    }

    // ── 并行 batch 顺序不变 ─────────────────────────────────────────

    @Test
    void parallelBatchPreservesOrder() {
        var tool1 = new FailingReadTool("read_a");
        tool1.failTimes = 2;
        var tool2 = new FailingReadTool("read_b");
        tool2.failTimes = 0;

        var tr = registryWith(tool1);
        tr.register(tool2);

        var events = new CopyOnWriteArrayList<RunEventPayload>();
        var control = CancellationTree.root(null,
            TokenBudget.unlimited(), WorkBudgetLedger.of(Long.MAX_VALUE, Long.MAX_VALUE));
        var executor = new ToolCallExecutor(tr, null,
            new DefaultToolRetryPolicy(new Random(1), 3), RetrySleeper.noop());

        var batch = executor.executeBatch(
            List.of(tc("c1", "read_a"), tc("c2", "read_b")),
            ctx(control, events));

        assertThat(batch.results()).hasSize(2);
        assertThat(batch.results().get(0).toolCallId()).isEqualTo("c1");
        assertThat(batch.results().get(1).toolCallId()).isEqualTo("c2");
    }

    // ── 副作用工具不进入 retry loop ─────────────────────────────────

    @Test
    void sideEffectToolDoesNotRetry() {
        var tool = new Tool() {
            final AtomicInteger calls = new AtomicInteger(0);
            @Override public String name() { return "writer"; }
            @Override public String description() { return "test"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public boolean isReadOnly() { return false; }

            @Override
            public ToolMetadata metadata() {
                return new ToolMetadata("writer", "test", null, null,
                    new ToolBehavior(false, ToolRiskLevel.HIGH, true, false, true, true,
                        Set.of(ToolSideEffect.FILE_WRITE)),
                    ToolExecutionPolicy.defaults(),
                    ToolMetadataProvenance.builtin("writer"));
            }

            @Override
            public Result<String> execute(String arguments) {
                calls.incrementAndGet();
                return new Result.Err<>(new Result.ErrorInfo("IO_ERROR", "error"));
            }
        };

        var tr = registryWith(tool);
        var events = new CopyOnWriteArrayList<RunEventPayload>();
        var control = CancellationTree.root(null,
            TokenBudget.unlimited(), WorkBudgetLedger.of(Long.MAX_VALUE, Long.MAX_VALUE));
        var executor = new ToolCallExecutor(tr, null,
            new DefaultToolRetryPolicy(new Random(1), 3), RetrySleeper.noop());

        var batch = executor.executeBatch(
            List.of(tc("c1", "writer")), ctx(control, events));

        assertThat(batch.results()).hasSize(1);
        var scheduled = events.stream()
            .filter(e -> e instanceof ToolRetryScheduledPayload).count();
        assertThat(scheduled).isEqualTo(0);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private ToolCall tc(String id, String name) {
        return new ToolCall(id, name, mapper.createObjectNode());
    }
}
