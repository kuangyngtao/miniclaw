package com.miniclaw.engine.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    // === Mock LLMProvider ===
    static class MockProvider implements LLMProvider {
        int turn = 0;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            turn++;
            if (turn == 1) {
                // 第一轮：返回一个 tool_call（bash ls）
                JsonNode args = mapper.createObjectNode().put("command", "ls -la");
                return Message.assistantWithTools(
                    List.of(new ToolCall("call_123", "bash", args)));
            }
            // 第二轮：纯文本，任务完成
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

    // === Test ===
    @Test
    void shouldCompleteReActLoopWithTwoTurns() {
        MockProvider mockProvider = new MockProvider();
        MockRegistry mockRegistry = new MockRegistry();
        AgentEngine engine = new AgentEngine(mockProvider, mockRegistry, "/tmp/work");

        String result = engine.run("Check files in current directory");

        // 引擎应完成两轮对话后退出
        assertThat(mockProvider.turn).isEqualTo(2);
        assertThat(result).contains("task complete");
    }
}
