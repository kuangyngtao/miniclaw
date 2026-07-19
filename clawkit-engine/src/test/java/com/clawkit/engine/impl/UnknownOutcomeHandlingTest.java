package com.clawkit.engine.impl;

import com.clawkit.engine.ThinkingMode;
import com.clawkit.provider.LLMProvider;
import com.clawkit.tools.Result;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolBehavior;
import com.clawkit.tools.ToolExecutionPolicy;
import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolMetadataProvenance;
import com.clawkit.tools.ToolOutputStats;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.ToolSideEffect;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.Role;
import com.clawkit.tools.schema.ToolCall;
import com.clawkit.tools.schema.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** P1-G2：结果未知（EFFECT_UNKNOWN）不得被当作确定失败注入推理。 */
class UnknownOutcomeHandlingTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 副作用工具：执行返回 TIMED_OUT（远端结果未知） */
    static class TimingOutWriteTool implements Tool {
        @Override public String name() { return "slow_write"; }
        @Override public String description() { return "side-effect tool that times out"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public boolean isReadOnly() { return false; }

        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(name(), description(), null, null,
                new ToolBehavior(false, ToolRiskLevel.MEDIUM, false, false, false, false,
                    Set.of(ToolSideEffect.FILE_WRITE)),
                new ToolExecutionPolicy(Duration.ofSeconds(5), 8000,
                    ToolExecutionPolicy.OutputTruncation.HEAD,
                    ToolExecutionPolicy.ToolConcurrency.SERIAL),
                ToolMetadataProvenance.builtin(name()));
        }

        @Override
        public Result<String> execute(String arguments) {
            throw new UnsupportedOperationException("V2 only");
        }

        @Override
        public com.clawkit.tools.action.ActionDescriptor describeAction(ToolExecutionRequest req) {
            // 目标含 runId：测试重复执行不受历史 OUTCOME_UNKNOWN sticky 锁影响
            String runId = req.scope() != null ? req.scope().runId() : "unknown";
            return new com.clawkit.tools.action.ActionDescriptor(
                "test.slow_write", "test:slow_write:" + runId,
                com.clawkit.tools.action.Digests.sha256Hex(String.valueOf(req.arguments())),
                ToolRiskLevel.MEDIUM,
                com.clawkit.tools.action.Reversibility.COMPENSATABLE,
                com.clawkit.tools.action.ActionReliability.none(),
                com.clawkit.tools.action.VerificationMode.MANUAL_REQUIRED,
                java.util.List.of(), java.util.List.of(), "", "");
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionRequest req) {
            return ToolExecutionResult.timedOut(req.toolCallId(), name(),
                "partial output", 5000, ToolOutputStats.EMPTY, metadata());
        }
    }

    /** 记录每轮完整上下文的 Provider */
    static class SpyProvider implements LLMProvider {
        final List<List<Message>> seen = new ArrayList<>();
        int turn;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> tools) {
            seen.add(List.copyOf(messages));
            if (++turn == 1) {
                return Message.assistantWithTools(List.of(
                    new ToolCall("c1", "slow_write", mapper.createObjectNode())));
            }
            return Message.assistant("done");
        }
    }

    @Test
    void shouldInjectUnknownOutcomeWarningInsteadOfTreatingTimeoutAsFailure() {
        SpyProvider provider = new SpyProvider();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TimingOutWriteTool());
        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work", ThinkingMode.OFF);

        String result = engine.run("write something");

        assertThat(result).isEqualTo("done");
        assertThat(provider.seen).hasSize(2);
        // 第二轮上下文必须包含结构化"结果未知"警告
        List<Message> secondTurn = provider.seen.get(1);
        assertThat(secondTurn.stream()
            .filter(m -> m.role() == Role.SYSTEM && m.content() != null)
            .anyMatch(m -> m.content().contains("结果未知")
                && m.content().contains("不得自动重复执行")))
            .as("EFFECT_UNKNOWN 必须注入安全警告: %s",
                secondTurn.stream().map(Message::content).toList())
            .isTrue();
    }
}
