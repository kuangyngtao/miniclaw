package com.miniclaw.engine.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.engine.ThinkingMode;
import com.miniclaw.provider.LLMException;
import com.miniclaw.provider.LLMProvider;
import com.miniclaw.tools.Registry;
import com.miniclaw.tools.Tool;
import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.ToolCall;
import com.miniclaw.tools.schema.ToolDefinition;
import com.miniclaw.tools.schema.ToolResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentEngineTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    // === MockProvider: OFF 模式（单阶段） ===
    static class MockProvider implements LLMProvider {
        int turn = 0;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            turn++;
            if (turn == 1) {
                JsonNode args = mapper.createObjectNode().put("command", "ls -la");
                return Message.assistantWithTools(
                    List.of(new ToolCall("call_123", "bash", args)));
            }
            return Message.assistant(
                "I see file list containing main.go — task complete!");
        }
    }

    // === MockProvider: TWO_STAGE 模式（三阶段） ===
    // 每轮: Stage1(空工具→推理) → Stage2(带工具→执行)
    static class TwoStageMockProvider implements LLMProvider {
        int callCount = 0;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            callCount++;
            boolean isStage1 = availableTools.isEmpty();

            // 第一轮
            if (callCount <= 2) {
                if (isStage1) {
                    // Stage 1: 推理规划（无工具）
                    return Message.assistant(
                        "我需要查看当前目录的文件列表。"
                        + "计划: 1) 用 bash ls -la 列出文件 2) 分析结果后回复用户");
                }
                // Stage 2: 带工具执行
                JsonNode args = mapper.createObjectNode().put("command", "ls -la");
                return Message.assistantWithTools(
                    List.of(new ToolCall("call_456", "bash", args)));
            }

            // 第二轮
            if (isStage1) {
                return Message.assistant(
                    "已获取文件列表，包含 main.go。任务完成，无需更多工具调用。");
            }
            return Message.assistant(
                "I see file list containing main.go — task complete!");
        }
    }

    // === Mock Registry ===
    static class MockRegistry implements Registry {
        @Override
        public List<ToolDefinition> getAvailableTools() {
            return List.of(new ToolDefinition("bash", "execute shell", null));
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.success(call.id(),
                "-rw-r--r--  1 user group  234 Oct 24 10:00 main.go\n");
        }

        @Override
        public void register(Tool tool) {}

        @Override
        public Optional<Tool> lookup(String name) {
            return Optional.empty();
        }
    }

    // === Test 1: OFF 模式 — 单阶段 ReAct ===
    @Test
    void shouldCompleteReActLoopInOffMode() {
        MockProvider mockProvider = new MockProvider();
        MockRegistry mockRegistry = new MockRegistry();
        AgentEngine engine = new AgentEngine(mockProvider, mockRegistry, "/tmp/work",
            ThinkingMode.OFF);

        String result = engine.run("Check files in current directory");

        assertThat(mockProvider.turn).isEqualTo(2);
        assertThat(result).contains("task complete");
    }

    // === Test 2: TWO_STAGE 模式 — 先规划再执行 ===
    @Test
    void shouldCompleteTwoStageThinkingLoop() {
        TwoStageMockProvider mockProvider = new TwoStageMockProvider();
        MockRegistry mockRegistry = new MockRegistry();
        AgentEngine engine = new AgentEngine(mockProvider, mockRegistry, "/tmp/work",
            ThinkingMode.TWO_STAGE);

        String result = engine.run("Check files in current directory");

        // TWO_STAGE: 每轮2次调用 × 2轮 = 4次
        assertThat(mockProvider.callCount).isEqualTo(4);
        assertThat(result).contains("task complete");
    }

    // === Test 3: 默认构造器 = OFF 模式 ===
    @Test
    void shouldDefaultToOffMode() {
        MockProvider mockProvider = new MockProvider();
        MockRegistry mockRegistry = new MockRegistry();
        AgentEngine engine = new AgentEngine(mockProvider, mockRegistry, "/tmp/work");

        assertThat(engine.thinkingMode()).isEqualTo(ThinkingMode.OFF);
    }

    // === 错误处理测试 ===

    // MockProvider: OFF 模式直接失败
    static class FailingOffProvider implements LLMProvider {
        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            throw new LLMException("Claude API 连接超时 (60s)");
        }
    }

    // MockProvider: TWO_STAGE 阶段1 失败
    static class FailingPhase1Provider implements LLMProvider {
        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            if (availableTools.isEmpty()) {
                throw new LLMException("Claude API 返回 HTTP 500");
            }
            return Message.assistant("never reached");
        }
    }

    // MockProvider: TWO_STAGE 阶段2 失败
    static class FailingPhase2Provider implements LLMProvider {
        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            if (availableTools.isEmpty()) {
                return Message.assistant("规划完成，准备执行");
            }
            throw new LLMException("Claude API 返回 HTTP 429 重试耗尽");
        }
    }

    @Test
    void shouldReturnErrorOnOffModeFailure() {
        FailingOffProvider mockProvider = new FailingOffProvider();
        MockRegistry mockRegistry = new MockRegistry();
        AgentEngine engine = new AgentEngine(mockProvider, mockRegistry, "/tmp/work",
            ThinkingMode.OFF);

        String result = engine.run("Do something");

        assertThat(result).startsWith("[A-002]");
        assertThat(result).contains("Claude API 连接超时");
    }

    @Test
    void shouldReturnErrorOnTwoStagePhase1Failure() {
        FailingPhase1Provider mockProvider = new FailingPhase1Provider();
        MockRegistry mockRegistry = new MockRegistry();
        AgentEngine engine = new AgentEngine(mockProvider, mockRegistry, "/tmp/work",
            ThinkingMode.TWO_STAGE);

        String result = engine.run("Check files");

        assertThat(result).startsWith("[A-002]");
        assertThat(result).contains("慢思考规划阶段");
        assertThat(result).contains("HTTP 500");
    }

    @Test
    void shouldReturnErrorOnTwoStagePhase2Failure() {
        FailingPhase2Provider mockProvider = new FailingPhase2Provider();
        MockRegistry mockRegistry = new MockRegistry();
        AgentEngine engine = new AgentEngine(mockProvider, mockRegistry, "/tmp/work",
            ThinkingMode.TWO_STAGE);

        String result = engine.run("Check files");

        assertThat(result).startsWith("[A-002]");
        assertThat(result).contains("HTTP 429 重试耗尽");
    }
}
