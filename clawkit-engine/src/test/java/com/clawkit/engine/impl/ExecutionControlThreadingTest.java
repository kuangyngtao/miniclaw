package com.clawkit.engine.impl;

import com.clawkit.engine.ThinkingMode;
import com.clawkit.observability.RunCompletedPayload;
import com.clawkit.observability.RunEventPayload;
import com.clawkit.observability.RunStatus;
import com.clawkit.provider.LLMProvider;
import com.clawkit.reliability.CancellationTree;
import com.clawkit.tools.DefaultApprovalGrantCache;
import com.clawkit.tools.PermissionMode;
import com.clawkit.tools.Registry;
import com.clawkit.tools.Result;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolBehavior;
import com.clawkit.tools.ToolExecutionPolicy;
import com.clawkit.tools.ToolExecutionStatus;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolMetadataProvenance;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolCall;
import com.clawkit.tools.schema.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** P1-G1：取消、deadline 和预算贯穿 ReAct / Tool / SubAgent 链路。 */
class ExecutionControlThreadingTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 计数 Provider：永远返回纯文本（除非配置了行为） */
    static class CountingProvider implements LLMProvider {
        final AtomicInteger calls = new AtomicInteger();
        Runnable onFirstCall;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> tools) {
            int n = calls.incrementAndGet();
            if (n == 1 && onFirstCall != null) {
                onFirstCall.run();
                var args = mapper.createObjectNode().put("path", "a.txt");
                return Message.assistantWithTools(List.of(new ToolCall("c1", "probe", args)));
            }
            return Message.assistant("done");
        }
    }

    /** 可编排工具：serial 只读 + 记录执行 */
    static class ProbeTool implements Tool {
        final String toolName;
        final AtomicBoolean executed = new AtomicBoolean(false);
        final Runnable onExecute;
        final CountDownLatch blockUntil;

        ProbeTool(String name, Runnable onExecute, CountDownLatch blockUntil) {
            this.toolName = name;
            this.onExecute = onExecute;
            this.blockUntil = blockUntil;
        }

        @Override public String name() { return toolName; }
        @Override public String description() { return "probe"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(toolName, "probe", null, null,
                new ToolBehavior(true, ToolRiskLevel.LOW, false, true, false, false, Set.of()),
                new ToolExecutionPolicy(Duration.ofSeconds(10), 8000,
                    ToolExecutionPolicy.OutputTruncation.HEAD,
                    ToolExecutionPolicy.ToolConcurrency.SERIAL),
                ToolMetadataProvenance.builtin(toolName));
        }

        @Override
        public Result<String> execute(String arguments) {
            executed.set(true);
            if (onExecute != null) onExecute.run();
            if (blockUntil != null) {
                try {
                    blockUntil.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Result.Err<>(new Result.ErrorInfo("INTERRUPTED", "interrupted"));
                }
            }
            return new Result.Ok<>("ok");
        }
    }

    private static ToolExecutionContext ctx(CancellationTree control) {
        return new ToolExecutionContext("run-1", 1, PermissionMode.AUTO,
            new DefaultPermissionPolicy(), null, null,
            new InternalToolRouter(), new DefaultApprovalGrantCache(), control);
    }

    // ── ToolCallExecutor：串行取消 ─────────────────────────────────

    @Test
    void shouldNotStartRemainingSerialToolsAfterCancel() {
        CancellationTree control = CancellationTree.unbounded();
        ProbeTool first = new ProbeTool("first", control::cancel, null);
        ProbeTool second = new ProbeTool("second", null, null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(first);
        registry.register(second);
        ToolCallExecutor executor = new ToolCallExecutor(registry);

        var calls = List.of(
            new ToolCall("c1", "first", mapper.createObjectNode()),
            new ToolCall("c2", "second", mapper.createObjectNode()));
        var batch = executor.executeBatch(calls, ctx(control));

        assertThat(first.executed).isTrue();
        assertThat(second.executed).isFalse();
        assertThat(batch.results()).hasSize(2);
        assertThat(batch.results().get(0).status()).isEqualTo(ToolExecutionStatus.SUCCESS);
        var cancelledResult = batch.results().get(1);
        assertThat(cancelledResult.status()).isEqualTo(ToolExecutionStatus.CANCELLED);
        // 关键不变量：未派发（只读工具派生为 NO_EFFECT_CONFIRMED，同样安全）
        assertThat(cancelledResult.failureClass())
            .isEqualTo(com.clawkit.tools.action.FailureClass.CANCELLED_BEFORE_DISPATCH);
        assertThat(cancelledResult.effectCertainty()).isIn(
            EffectCertainty.NOT_DISPATCHED, EffectCertainty.NO_EFFECT_CONFIRMED);
    }

    // ── ToolCallExecutor：并行取消归并 ─────────────────────────────

    @Test
    void shouldResolveCancelledParallelSlotsWithTerminalStates() throws Exception {
        CancellationTree control = CancellationTree.unbounded();
        CountDownLatch never = new CountDownLatch(1); // 不放行 → 依赖取消中断
        ProbeTool slow1 = parallelSafeTool("slow1", never);
        ProbeTool slow2 = parallelSafeTool("slow2", never);
        ToolRegistry registry = new ToolRegistry();
        registry.register(slow1);
        registry.register(slow2);
        ToolCallExecutor executor = new ToolCallExecutor(registry);

        var calls = List.of(
            new ToolCall("c1", "slow1", mapper.createObjectNode()),
            new ToolCall("c2", "slow2", mapper.createObjectNode()));

        var resultRef = new java.util.concurrent.atomic.AtomicReference<ToolExecutionBatchResult>();
        long start = System.currentTimeMillis();
        Thread worker = Thread.ofVirtual().start(() ->
            resultRef.set(executor.executeBatch(calls, ctx(control))));

        Thread.sleep(300); // 等两个槽位启动
        control.cancel();
        worker.join(10_000);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(resultRef.get()).as("batch must merge terminal states after cancel").isNotNull();
        assertThat(elapsed).isLessThan(8000);
        assertThat(resultRef.get().results()).hasSize(2);
        for (var r : resultRef.get().results()) {
            assertThat(r.status()).isIn(
                ToolExecutionStatus.CANCELLED, ToolExecutionStatus.TOOL_ERROR,
                ToolExecutionStatus.INTERNAL_ERROR);
        }
    }

    private static ProbeTool parallelSafeTool(String name, CountDownLatch latch) {
        return new ProbeTool(name, null, latch) {
            @Override
            public ToolMetadata metadata() {
                return new ToolMetadata(name, "probe", null, null,
                    new ToolBehavior(true, ToolRiskLevel.LOW, false, true, false, false, Set.of()),
                    new ToolExecutionPolicy(Duration.ofSeconds(10), 8000,
                        ToolExecutionPolicy.OutputTruncation.HEAD,
                        ToolExecutionPolicy.ToolConcurrency.PARALLEL_SAFE),
                    ToolMetadataProvenance.builtin(name));
            }
        };
    }

    // ── Engine：预算与 deadline 硬拦截 ─────────────────────────────

    @Test
    void shouldRefuseProviderCallWhenBudgetExhausted() {
        CountingProvider provider = new CountingProvider();
        AgentEngine engine = new AgentEngine(provider, new AgentEngineTest.MockRegistry(),
            "/tmp/work", ThinkingMode.OFF);
        engine.setRunLimits(null, 0L);
        List<RunEventPayload> events = new CopyOnWriteArrayList<>();
        engine.addRecorder((p, rid, prid, tn, at) -> events.add(p));

        String result = engine.run("hello");

        assertThat(provider.calls.get()).isZero();
        assertThat(result).contains("[A-007]");
        assertThat(lastRunStatus(events)).isEqualTo(RunStatus.BUDGET_EXHAUSTED);
    }

    @Test
    void shouldRefuseProviderCallWhenOnlyPartialTokenReservationIsAvailable() {
        CountingProvider provider = new CountingProvider();
        AgentEngine engine = new AgentEngine(provider, new AgentEngineTest.MockRegistry(),
            "/tmp/work", ThinkingMode.OFF);
        engine.setRunLimits(null, 100L);

        String result = engine.run("hello");

        assertThat(provider.calls.get()).isZero();
        assertThat(result).contains("[A-007]");
    }

    @Test
    void shouldEnforceProviderCallCountBudgetBeforeNetworkCall() {
        CountingProvider provider = new CountingProvider();
        AgentEngine engine = new AgentEngine(provider, new AgentEngineTest.MockRegistry(),
            "/tmp/work", ThinkingMode.OFF);
        engine.setRunLimits(null, null, 0L, null);

        String result = engine.run("hello");

        assertThat(provider.calls.get()).isZero();
        assertThat(result).contains("[A-007]");
    }

    @Test
    void shouldEnforceToolCallCountBudgetBeforeToolExecution() {
        ProbeTool probe = new ProbeTool("probe", null, null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(probe);
        CancellationTree control = CancellationTree.root(null,
            com.clawkit.tools.control.TokenBudget.unlimited(),
            com.clawkit.reliability.WorkBudgetLedger.of(Long.MAX_VALUE, 0));
        ToolCallExecutor executor = new ToolCallExecutor(registry);

        var batch = executor.executeBatch(
            List.of(new ToolCall("c1", "probe", mapper.createObjectNode())),
            ctx(control));

        assertThat(probe.executed).isFalse();
        // P1-A1：budget exhausted 通过 halted(...) 正确映射为 CANCELLED + BUDGET_EXHAUSTED
        assertThat(batch.results().get(0).status()).isEqualTo(ToolExecutionStatus.CANCELLED);
        assertThat(batch.results().get(0).output()).contains("预算已耗尽");
    }

    @Test
    void shouldRefuseProviderCallWhenDeadlineExceeded() {
        CountingProvider provider = new CountingProvider();
        AgentEngine engine = new AgentEngine(provider, new AgentEngineTest.MockRegistry(),
            "/tmp/work", ThinkingMode.OFF);
        engine.setRunLimits(Duration.ZERO, null);
        List<RunEventPayload> events = new CopyOnWriteArrayList<>();
        engine.addRecorder((p, rid, prid, tn, at) -> events.add(p));

        String result = engine.run("hello");

        assertThat(provider.calls.get()).isZero();
        assertThat(result).contains("[A-006]");
        assertThat(lastRunStatus(events)).isEqualTo(RunStatus.DEADLINE_EXCEEDED);
    }

    // ── Engine：interrupt() 级联到工具与下一轮 ─────────────────────

    @Test
    void shouldStopToolsAndLoopAfterInterruptDuringProviderCall() {
        CountingProvider provider = new CountingProvider();
        ProbeTool probe = new ProbeTool("probe", null, null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(probe);

        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work", ThinkingMode.OFF);
        provider.onFirstCall = engine::interrupt; // 模型响应期间用户按下 Ctrl+C

        String result = engine.run("hello");

        assertThat(result).contains("[A-001]");
        assertThat(provider.calls.get()).isEqualTo(1); // 取消后不再发起新 Provider 调用
        assertThat(probe.executed).as("取消后不启动新工具").isFalse();
    }

    // ── SubAgent：父控制级联 ───────────────────────────────────────

    @Test
    void shouldCascadeParentCancelIntoChildEngine() {
        CountingProvider provider = new CountingProvider();
        AgentEngine child = new AgentEngine(provider, new AgentEngineTest.MockRegistry(),
            "/tmp/work", ThinkingMode.OFF);
        CancellationTree parent = CancellationTree.unbounded();
        child.attachParentControl(parent);
        parent.cancel();

        String result = child.run("child task");

        assertThat(result).contains("[A-001]");
        assertThat(provider.calls.get()).isZero();
    }

    // ── SubAgent：父预算共享 ───────────────────────────────────────

    @Test
    void shouldShareParentBudgetWithChildEngine() {
        CountingProvider provider = new CountingProvider();
        AgentEngine child = new AgentEngine(provider, new AgentEngineTest.MockRegistry(),
            "/tmp/work", ThinkingMode.OFF);
        CancellationTree parent = CancellationTree.root(null,
            com.clawkit.reliability.BudgetLedger.of(0));
        child.attachParentControl(parent);

        String result = child.run("child task");

        assertThat(result).contains("[A-007]");
        assertThat(provider.calls.get()).isZero();
    }

    private static RunStatus lastRunStatus(List<RunEventPayload> events) {
        return events.stream()
            .filter(p -> p instanceof RunCompletedPayload)
            .map(p -> ((RunCompletedPayload) p).status())
            .reduce((a, b) -> a)   // 第一个 RunCompleted 是引擎真实终态
            .orElse(null);
    }
}
