package com.clawkit.engine.impl;

import com.clawkit.context.CompactionAudit;
import com.clawkit.context.CompactionLevel;
import com.clawkit.context.CompactionRequest;
import com.clawkit.context.CompactionResult;
import com.clawkit.context.ContextBudgetReport;
import com.clawkit.context.ContextPipeline;
import com.clawkit.context.ContextRequest;
import com.clawkit.context.ContextSection;
import com.clawkit.context.ModelContext;
import com.clawkit.engine.AgentRuntimeDependencies;
import com.clawkit.engine.ProviderGateway;
import com.clawkit.engine.RunScope;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.observability.CompactCompletedPayload;
import com.clawkit.observability.RunCompletedPayload;
import com.clawkit.observability.RunEventPayload;
import com.clawkit.observability.RunStatus;
import com.clawkit.provider.ModelRequest;
import com.clawkit.provider.ModelResponse;
import com.clawkit.provider.StreamObserver;
import com.clawkit.tools.Registry;
import com.clawkit.tools.Tool;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolCall;
import com.clawkit.tools.schema.ToolDefinition;
import com.clawkit.tools.schema.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveCompactionEngineTest {

    @Test
    void compactFailureStopsBeforeMainProviderCallAndIsObservable() {
        AtomicInteger providerCalls = new AtomicInteger();
        ProviderGateway gateway = new ProviderGateway() {
            @Override public ModelResponse generate(ModelRequest request, RunScope scope) {
                providerCalls.incrementAndGet();
                throw new AssertionError("main provider must not be called after compact failure");
            }

            @Override public ModelResponse generateStream(ModelRequest request, RunScope scope,
                                                           StreamObserver observer) {
                providerCalls.incrementAndGet();
                throw new AssertionError("main provider must not be called after compact failure");
            }
        };
        ContextBudgetReport hard = new ContextBudgetReport(980, 0.98,
            ContextBudgetReport.BudgetStatus.HARD_LIMIT,
            Map.of(ContextSection.HISTORY, 980), 1, "hard limit");
        ContextPipeline pipeline = new ContextPipeline() {
            @Override public ModelContext build(ContextRequest request) {
                List<Message> messages = List.of(Message.user("oversized context"));
                return new ModelContext(List.of(), messages, hard);
            }

            @Override public CompactionResult compact(CompactionRequest request) {
                var audit = new CompactionAudit("GENERAL", List.of("required-1"),
                    List.of(), List.of(), 0, 3, "COMPACT_HARD_LIMIT",
                    CompactionLevel.L4_FAILED, "COMPACT_HARD_LIMIT");
                return new CompactionResult(request.modelContext(), hard, hard,
                    List.of(), List.of("l2-extractive"), 1, 1, true, audit);
            }
        };
        List<RunEventPayload> events = new ArrayList<>();
        var recorder = (com.clawkit.observability.RunRecorder)
            (payload, runId, parentRunId, turn, time) -> events.add(payload);
        var deps = new AgentRuntimeDependencies(gateway, pipeline, new EmptyRegistry(),
            1_000, "cl100k_base", recorder, null, null);
        AgentEngine engine = new AgentEngine(deps, "/tmp/work", ThinkingMode.OFF, "");

        String output = engine.run("continue");

        assertThat(output).contains("COMPACT_HARD_LIMIT", "未调用主任务模型");
        assertThat(providerCalls).hasValue(0);
        assertThat(events).filteredOn(CompactCompletedPayload.class::isInstance)
            .singleElement().satisfies(payload -> {
                var compact = (CompactCompletedPayload) payload;
                assertThat(compact.failed()).isTrue();
                assertThat(compact.level()).isEqualTo("L4_FAILED");
                assertThat(compact.failureCode()).isEqualTo("COMPACT_HARD_LIMIT");
            });
        assertThat(events).filteredOn(RunCompletedPayload.class::isInstance)
            .singleElement().satisfies(payload -> assertThat(((RunCompletedPayload) payload).status())
                .isEqualTo(RunStatus.COMPACT_FAILED));
    }

    private static final class EmptyRegistry implements Registry {
        @Override public List<ToolDefinition> getAvailableTools() { return List.of(); }
        @Override public ToolResult execute(ToolCall call) {
            throw new AssertionError("tools are not expected");
        }
        @Override public void register(Tool tool) {}
        @Override public Optional<Tool> lookup(String name) { return Optional.empty(); }
        @Override public boolean isReadOnly(String toolName) { return false; }
    }
}
