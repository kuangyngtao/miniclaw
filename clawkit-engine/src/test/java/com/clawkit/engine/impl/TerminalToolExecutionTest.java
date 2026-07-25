package com.clawkit.engine.impl;

import com.clawkit.reliability.CancellationTree;
import com.clawkit.reliability.WorkBudgetLedger;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.provider.LLMProvider;
import com.clawkit.tools.ApprovalGrantCache;
import com.clawkit.tools.PermissionMode;
import com.clawkit.tools.Result;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolBehavior;
import com.clawkit.tools.ToolControlPolicy;
import com.clawkit.tools.ToolExecutionPolicy;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolMetadataProvenance;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.control.TokenBudget;
import com.clawkit.tools.schema.ToolCall;
import com.clawkit.tools.schema.ToolDefinition;
import com.clawkit.tools.schema.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalToolExecutionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void trustedTerminalToolCompletesOnCertainSuccess() {
        CountingTool terminal = new CountingTool("submit", true,
            ToolMetadataProvenance.builtin("submit"));
        ToolExecutionBatchResult result = execute(List.of(call("c1", "submit")), terminal);

        assertThat(result.loopDecision()).isEqualTo(ToolLoopDecision.COMPLETE);
        assertThat(result.finalOutput()).isEqualTo("accepted");
        assertThat(terminal.calls.get()).isEqualTo(1);
    }

    @Test
    void mixedTerminalBatchExecutesNoTools() {
        CountingTool terminal = new CountingTool("submit", true,
            ToolMetadataProvenance.builtin("submit"));
        CountingTool read = new CountingTool("read", false,
            ToolMetadataProvenance.builtin("read"));
        ToolExecutionBatchResult result = execute(
            List.of(call("c1", "submit"), call("c2", "read")), terminal, read);

        assertThat(result.loopDecision()).isEqualTo(ToolLoopDecision.CONTINUE);
        assertThat(result.results()).allMatch(r ->
            "TERMINAL_TOOL_MUST_BE_EXCLUSIVE".equals(r.errorCode()));
        assertThat(terminal.calls.get()).isZero();
        assertThat(read.calls.get()).isZero();
    }

    @Test
    void mcpMetadataCannotTerminateTheRun() {
        CountingTool terminal = new CountingTool("remote_submit", true,
            ToolMetadataProvenance.mcp("server", "remote_submit", true));
        ToolExecutionBatchResult result = execute(
            List.of(call("c1", "remote_submit")), terminal);

        assertThat(result.loopDecision()).isEqualTo(ToolLoopDecision.CONTINUE);
        assertThat(terminal.calls.get()).isEqualTo(1);
    }

    @Test
    void failedTerminalToolDoesNotComplete() {
        CountingTool terminal = new CountingTool("submit", true,
            ToolMetadataProvenance.builtin("submit"));
        terminal.fail = true;
        ToolExecutionBatchResult result = execute(List.of(call("c1", "submit")), terminal);

        assertThat(result.loopDecision()).isEqualTo(ToolLoopDecision.CONTINUE);
    }

    @Test
    void agentEngineSkipsTheTrailingProviderCall() {
        CountingTool terminal = new CountingTool("submit", true,
            ToolMetadataProvenance.builtin("submit"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(terminal);
        AtomicInteger providerCalls = new AtomicInteger();
        LLMProvider provider = new LLMProvider() {
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> tools) {
                providerCalls.incrementAndGet();
                return Message.assistantWithTools(List.of(call("c1", "submit")));
            }
        };
        AgentEngine engine = new AgentEngine(provider, registry, ".", ThinkingMode.OFF);

        String output = engine.run("finish");

        assertThat(output).isEqualTo("accepted");
        assertThat(providerCalls.get()).isEqualTo(1);
        assertThat(terminal.calls.get()).isEqualTo(1);
    }

    private static ToolExecutionBatchResult execute(List<ToolCall> calls, Tool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (Tool tool : tools) registry.register(tool);
        var control = CancellationTree.root(null, TokenBudget.unlimited(),
            WorkBudgetLedger.of(Long.MAX_VALUE, Long.MAX_VALUE));
        var context = new ToolExecutionContext("run-1", 1, PermissionMode.AUTO,
            new DefaultPermissionPolicy(), null, (p, rid, prid, turn, at) -> { },
            new InternalToolRouter(), ApprovalGrantCache.noop(), control);
        return new ToolCallExecutor(registry).executeBatch(calls, context);
    }

    private static ToolCall call(String id, String name) {
        return new ToolCall(id, name, MAPPER.createObjectNode());
    }

    private static final class CountingTool implements Tool {
        private final String name;
        private final boolean terminal;
        private final ToolMetadataProvenance provenance;
        private final AtomicInteger calls = new AtomicInteger();
        private boolean fail;

        private CountingTool(String name, boolean terminal, ToolMetadataProvenance provenance) {
            this.name = name;
            this.terminal = terminal;
            this.provenance = provenance;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "test"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(name, "test", null, null,
                new ToolBehavior(true, ToolRiskLevel.LOW, false, true,
                    false, false, Set.of()),
                ToolExecutionPolicy.readOnlyDefaults(), provenance,
                terminal ? ToolControlPolicy.COMPLETE_ON_SUCCESS : ToolControlPolicy.DEFAULT);
        }

        @Override
        public Result<String> execute(String arguments) {
            calls.incrementAndGet();
            return fail
                ? new Result.Err<>(new Result.ErrorInfo("INVALID", "rejected"))
                : new Result.Ok<>("accepted");
        }
    }
}
