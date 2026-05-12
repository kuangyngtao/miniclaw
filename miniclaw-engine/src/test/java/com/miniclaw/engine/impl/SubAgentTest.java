package com.miniclaw.engine.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.engine.PermissionMode;
import com.miniclaw.provider.LLMProvider;
import com.miniclaw.tools.Result;
import com.miniclaw.tools.Tool;
import com.miniclaw.tools.ToolRegistry;
import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.ToolCall;
import com.miniclaw.tools.schema.ToolDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubAgentTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    // === 测试工具 ===
    private static final List<String> executedToolNames = Collections.synchronizedList(new ArrayList<>());

    private static Tool testTool(String name, boolean readOnly) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test tool: " + name; }
            @Override public String inputSchema() { return "{}"; }
            @Override public boolean isReadOnly() { return readOnly; }
            @Override public Result<String> execute(String arguments) {
                executedToolNames.add(name);
                return new Result.Ok<>("ok: " + name);
            }
        };
    }

    private static ToolRegistry createTestRegistry() {
        executedToolNames.clear();
        ToolRegistry r = new ToolRegistry();
        r.register(testTool("glob", true));
        r.register(testTool("grep", true));
        r.register(testTool("bash", false));
        r.register(testTool("write", false));
        return r;
    }

    // === 1. explore 模式：子 Agent 仅能使用读工具 ===

    @Test
    void shouldRunExploreSubAgentWithReadOnlyTools() {
        ToolRegistry registry = createTestRegistry();

        LLMProvider provider = new LLMProvider() {
            int turn = 0;
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
                turn++;
                List<String> toolNames = availableTools.stream().map(ToolDefinition::name).toList();

                if (turn == 1) {
                    // 主 Agent 回合1: 调用 task(explore)
                    JsonNode args = mapper.createObjectNode()
                        .put("instruction", "search all java files")
                        .put("subagent_type", "explore");
                    return Message.assistantWithTools(List.of(
                        new ToolCall("task_1", "task", args)));
                }
                if (turn == 2) {
                    // 子 Agent: 应只有读工具
                    assertThat(toolNames).doesNotContain("task", "bash", "write");
                    assertThat(toolNames).contains("glob", "grep");
                    JsonNode args = mapper.createObjectNode().put("pattern", "*.java");
                    return Message.assistantWithTools(List.of(
                        new ToolCall("call_1", "glob", args)));
                }
                if (turn == 3) {
                    // 子 Agent 退出
                    return Message.assistant("found 3 java files");
                }
                // 主 Agent 最终回复
                return Message.assistant("analysis complete: 3 files found");
            }
        };

        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work");
        String result = engine.run("find java files");

        assertThat(result).contains("analysis complete");
        assertThat(executedToolNames).contains("glob");
        assertThat(executedToolNames).doesNotContain("bash", "write");
    }

    // === 2. general 模式：子 Agent 可使用写工具 ===

    @Test
    void shouldRunGeneralSubAgentWithWriteTools() {
        ToolRegistry registry = createTestRegistry();

        LLMProvider provider = new LLMProvider() {
            int turn = 0;
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
                turn++;
                List<String> toolNames = availableTools.stream().map(ToolDefinition::name).toList();

                if (turn == 1) {
                    JsonNode args = mapper.createObjectNode()
                        .put("instruction", "create a config file")
                        .put("subagent_type", "general");
                    return Message.assistantWithTools(List.of(
                        new ToolCall("task_1", "task", args)));
                }
                if (turn == 2) {
                    // 子 Agent: 应有全工具但不含 task
                    assertThat(toolNames).doesNotContain("task");
                    assertThat(toolNames).contains("glob", "grep", "bash", "write");
                    JsonNode args = mapper.createObjectNode()
                        .put("file", "config.properties")
                        .put("content", "key=value");
                    return Message.assistantWithTools(List.of(
                        new ToolCall("call_1", "write", args)));
                }
                if (turn == 3) {
                    return Message.assistant("config file created");
                }
                return Message.assistant("task done");
            }
        };

        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work");
        String result = engine.run("create config");

        assertThat(result).contains("task done");
        assertThat(executedToolNames).contains("write");
    }

    // === 3. 并行 SubAgent：多个 task 调用并发执行 ===

    @Test
    void shouldRunMultipleSubAgentsInParallel() {
        ToolRegistry registry = createTestRegistry();
        AtomicInteger subAgentRuns = new AtomicInteger(0);
        CountDownLatch insideSubAgent = new CountDownLatch(2);

        LLMProvider provider = new LLMProvider() {
            int turn = 0;
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
                turn++;
                List<String> toolNames = availableTools.stream().map(ToolDefinition::name).toList();

                if (turn == 1) {
                    // 主 Agent: 派发两个 task
                    JsonNode argsA = mapper.createObjectNode()
                        .put("instruction", "search java")
                        .put("subagent_type", "explore");
                    JsonNode argsB = mapper.createObjectNode()
                        .put("instruction", "search xml")
                        .put("subagent_type", "explore");
                    return Message.assistantWithTools(List.of(
                        new ToolCall("task_a", "task", argsA),
                        new ToolCall("task_b", "task", argsB)));
                }
                // 子 Agent 回合（两个并行子 Agent 共享 provider）
                if (!toolNames.contains("task")) {
                    subAgentRuns.incrementAndGet();
                    insideSubAgent.countDown();
                    return Message.assistant("sub search result");
                }
                // 主 Agent 收到两个结果后退出
                return Message.assistant("all searches done");
            }
        };

        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work");
        String result = engine.run("search both");

        assertThat(result).contains("all searches done");
        assertThat(subAgentRuns.get()).isEqualTo(2);
    }

    // === 4. 子 Agent 不持有 task 工具（防递归） ===

    @Test
    void shouldExcludeTaskToolFromSubAgent() {
        ToolRegistry registry = createTestRegistry();

        LLMProvider provider = new LLMProvider() {
            int turn = 0;
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
                turn++;
                List<String> toolNames = availableTools.stream().map(ToolDefinition::name).toList();

                if (turn == 1) {
                    JsonNode args = mapper.createObjectNode()
                        .put("instruction", "do something")
                        .put("subagent_type", "general");
                    return Message.assistantWithTools(List.of(
                        new ToolCall("task_1", "task", args)));
                }
                if (turn == 2) {
                    // 子 Agent: 验证工具列表中无 task
                    assertThat(toolNames).doesNotContain("task");
                    return Message.assistant("sub done");
                }
                // 主 Agent 后续轮次（task 合法存在）
                return Message.assistant("main done");
            }
        };

        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work");
        String result = engine.run("test no recursion");
        assertThat(result).contains("main done");
    }

    // === 5. PLAN 模式不暴露 task 工具 ===

    @Test
    void shouldNotExposeTaskInPlanMode() {
        ToolRegistry registry = createTestRegistry();

        LLMProvider provider = new LLMProvider() {
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
                List<String> toolNames = availableTools.stream().map(ToolDefinition::name).toList();
                assertThat(toolNames).doesNotContain("task");
                return Message.assistant("planning complete");
            }
        };

        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work");
        engine.setPermissionMode(PermissionMode.PLAN);
        String result = engine.run("plan this");
        assertThat(result).contains("planning complete");
    }

    // === 6. 子 Agent 继承主 Agent 权限模式 ===

    @Test
    void shouldInheritPermissionModeInSubAgent() {
        ToolRegistry registry = createTestRegistry();
        List<String> permissionChecked = Collections.synchronizedList(new ArrayList<>());

        LLMProvider provider = new LLMProvider() {
            int turn = 0;
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
                turn++;

                if (turn == 1) {
                    JsonNode args = mapper.createObjectNode()
                        .put("instruction", "write a file")
                        .put("subagent_type", "general");
                    return Message.assistantWithTools(List.of(
                        new ToolCall("task_1", "task", args)));
                }
                if (turn == 2) {
                    // 子 Agent: 调用写工具，应触发 permissionHandler
                    JsonNode args = mapper.createObjectNode()
                        .put("file", "test.txt")
                        .put("content", "data");
                    return Message.assistantWithTools(List.of(
                        new ToolCall("call_1", "write", args)));
                }
                if (turn == 3) {
                    return Message.assistant("file written");
                }
                return Message.assistant("task complete");
            }
        };

        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work");
        engine.setPermissionMode(PermissionMode.ASK);
        engine.setPermissionHandler(call -> {
            permissionChecked.add(call.name());
            return true; // 批准
        });

        String result = engine.run("write file");
        assertThat(result).contains("task complete");
        // 子 Agent 的写工具触发了权限检查
        assertThat(permissionChecked).contains("write");
    }
}
