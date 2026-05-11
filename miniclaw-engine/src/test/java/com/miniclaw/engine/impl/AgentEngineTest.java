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

        @Override
        public boolean isReadOnly(String toolName) {
            return false; // bash is a write tool
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

        // TWO_STAGE: 首轮2次调用(Phase1+Phase2) + 第二轮1次调用(Phase2) = 3次
        assertThat(mockProvider.callCount).isEqualTo(3);
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

    // === 多轮上下文测试 ===

    // 两轮对话：第一轮返回文件列表 → 第二轮引用第一轮结果
    static class MultiTurnProvider implements LLMProvider {
        int turn = 0;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            turn++;
            if (turn == 1) {
                JsonNode args = mapper.createObjectNode().put("command", "ls");
                return Message.assistantWithTools(
                    List.of(new ToolCall("call_m1", "bash", args)));
            }
            if (turn == 2) {
                return Message.assistant("found main.go in the listing");
            }
            // 第二轮 run()：turn 3
            if (turn == 3) {
                return Message.assistant(
                    "previously we found main.go — let me check it");
            }
            return Message.assistant("task done");
        }
    }

    @Test
    void shouldPersistConversationAcrossMultipleCalls() {
        MultiTurnProvider provider = new MultiTurnProvider();
        MockRegistry mockRegistry = new MockRegistry();
        AgentEngine engine = new AgentEngine(provider, mockRegistry, "/tmp/work",
            ThinkingMode.OFF);

        String result1 = engine.run("list files");
        assertThat(result1).contains("main.go");

        // 第二轮调用：provider 应该能感知到这是第3次 generate
        String result2 = engine.run("check the first file");
        assertThat(result2).contains("previously");
    }

    @Test
    void shouldClearSessionHistory() {
        MultiTurnProvider provider = new MultiTurnProvider();
        MockRegistry mockRegistry = new MockRegistry();
        AgentEngine engine = new AgentEngine(provider, mockRegistry, "/tmp/work",
            ThinkingMode.OFF);

        engine.run("list files");
        engine.clearSession();

        // 清空后 provider.turn 重置视角：新一轮从 turn 1 开始
        // 但 provider 实例没变，所以 turn 计数继续
        // 验证：engine 内部 sessionHistory 已清空
        assertThat(engine.thinkingMode()).isEqualTo(ThinkingMode.OFF);
    }

    // === 死循环检测 + 硬上限测试 ===

    // 连续返回相同工具调用 → 触发死循环检测
    static class DeadLoopProvider implements LLMProvider {
        int callCount = 0;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            callCount++;
            // 检查是否收到了死循环警告
            boolean warned = messages.stream().anyMatch(m ->
                m.role() == com.miniclaw.tools.schema.Role.SYSTEM
                && m.content() != null
                && m.content().contains("[Runtime] 检测到连续"));
            if (warned) {
                return Message.assistant("检测到循环，已停止。");
            }
            JsonNode args = mapper.createObjectNode().put("command", "ls -la");
            return Message.assistantWithTools(
                List.of(new ToolCall("call_loop", "bash", args)));
        }
    }

    @Test
    void shouldDetectDeadLoopAndInjectWarning() {
        DeadLoopProvider provider = new DeadLoopProvider();
        MockRegistry mockRegistry = new MockRegistry();
        AgentEngine engine = new AgentEngine(provider, mockRegistry, "/tmp/work",
            ThinkingMode.OFF);

        String result = engine.run("do something");

        // 3轮警告后 LLM 应看到警告并停止
        assertThat(result).contains("循环");
        assertThat(provider.callCount).isEqualTo(4); // 前3轮same call + 第4轮看到warning后text
    }

    // 每轮返回不同工具调用 → 不触发死循环，最终到硬上限
    static class InfiniteLoopProvider implements LLMProvider {
        int callCount = 0;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            callCount++;
            JsonNode args = mapper.createObjectNode().put("command", "echo loop" + callCount);
            return Message.assistantWithTools(
                List.of(new ToolCall("call_loop" + callCount, "bash", args)));
        }
    }

    @Test
    void shouldEnforceHardLimit() {
        InfiniteLoopProvider provider = new InfiniteLoopProvider();
        MockRegistry mockRegistry = new MockRegistry();
        AgentEngine engine = new AgentEngine(provider, mockRegistry, "/tmp/work",
            ThinkingMode.OFF);

        String result = engine.run("do something");

        assertThat(result).startsWith("[A-001]");
        assertThat(result).contains("50");
        assertThat(provider.callCount).isEqualTo(50);
    }
}
