package com.miniclaw.engine.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.engine.PermissionMode;
import com.miniclaw.provider.LLMProvider;
import com.miniclaw.tools.Registry;
import com.miniclaw.tools.Tool;
import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.ToolCall;
import com.miniclaw.tools.schema.ToolDefinition;
import com.miniclaw.tools.schema.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionModeTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    // === 共享 Registry：含读工具 (glob) 和写工具 (bash) ===
    static class DualToolRegistry implements Registry {
        final List<ToolCall> executedCalls = new ArrayList<>();

        @Override
        public List<ToolDefinition> getAvailableTools() {
            return List.of(
                new ToolDefinition("glob", "find files by pattern", null),
                new ToolDefinition("bash", "execute shell command", null)
            );
        }

        @Override
        public ToolResult execute(ToolCall call) {
            executedCalls.add(call);
            return ToolResult.success(call.id(), "ok: " + call.name());
        }

        @Override
        public void register(Tool tool) {}

        @Override
        public Optional<Tool> lookup(String name) {
            return Optional.empty();
        }
    }

    // === PLAN 模式：写工具被过滤 + 防御纵深拦截 ===

    // 返回 bash（写工具），但 PLAN 模式不允许 bash → 触防御纵深
    // turn 1: 返回 bash → 被拦截回注 → turn 2: 文本收尾
    static class PlanBypassProvider implements LLMProvider {
        int turn = 0;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            turn++;
            List<String> toolNames = availableTools.stream().map(ToolDefinition::name).toList();
            if (!toolNames.equals(List.of("glob"))) {
                throw new AssertionError("PLAN mode should only expose glob, got: " + toolNames);
            }
            if (turn == 1) {
                JsonNode args = mapper.createObjectNode().put("command", "ls");
                return Message.assistantWithTools(
                    List.of(new ToolCall("call_1", "bash", args)));
            }
            return Message.assistant("PLAN mode blocked bash — plan complete.");
        }
    }

    @Test
    void shouldFilterToolsAndBlockWriteInPlanMode() {
        DualToolRegistry registry = new DualToolRegistry();
        PlanBypassProvider provider = new PlanBypassProvider();
        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work");
        engine.setPermissionMode(PermissionMode.PLAN);

        String result = engine.run("test");

        // 写工具被拦截，未真正执行；引擎正常退出
        assertThat(registry.executedCalls).isEmpty();
        assertThat(result).isNotEmpty();
    }

    // PLAN 模式允许读工具
    static class PlanReadOnlyProvider implements LLMProvider {
        int turn = 0;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            turn++;
            if (turn == 1) {
                JsonNode args = mapper.createObjectNode().put("pattern", "*.java");
                return Message.assistantWithTools(
                    List.of(new ToolCall("call_2", "glob", args)));
            }
            return Message.assistant("found 3 java files");
        }
    }

    @Test
    void shouldAllowReadToolInPlanMode() {
        DualToolRegistry registry = new DualToolRegistry();
        PlanReadOnlyProvider provider = new PlanReadOnlyProvider();
        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work");
        engine.setPermissionMode(PermissionMode.PLAN);

        engine.run("find java files");

        assertThat(registry.executedCalls).hasSize(1);
        assertThat(registry.executedCalls.get(0).name()).isEqualTo("glob");
    }

    // === ASK 模式：用户确认 ===

    static class AskWriteProvider implements LLMProvider {
        int turn = 0;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            turn++;
            if (turn == 1) {
                JsonNode args = mapper.createObjectNode().put("command", "rm -rf /tmp/test");
                return Message.assistantWithTools(
                    List.of(new ToolCall("call_3", "bash", args)));
            }
            return Message.assistant("task done");
        }
    }

    @Test
    void shouldExecuteWhenUserApprovesInAskMode() {
        DualToolRegistry registry = new DualToolRegistry();
        AskWriteProvider provider = new AskWriteProvider();
        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work");
        engine.setPermissionMode(PermissionMode.ASK);
        engine.setPermissionHandler(call -> true);

        engine.run("clean up");

        assertThat(registry.executedCalls).hasSize(1);
        assertThat(registry.executedCalls.get(0).name()).isEqualTo("bash");
    }

    @Test
    void shouldBlockWhenUserDeniesInAskMode() {
        DualToolRegistry registry = new DualToolRegistry();
        AskWriteProvider provider = new AskWriteProvider();
        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work");
        engine.setPermissionMode(PermissionMode.ASK);
        engine.setPermissionHandler(call -> false);

        String result = engine.run("clean up");

        // 用户拒绝后工具未执行，引擎正常退出
        assertThat(registry.executedCalls).isEmpty();
        assertThat(result).isNotEmpty();
    }

    // ASK 模式：读+写混合，只对写工具确认
    static class AskMixedProvider implements LLMProvider {
        int turn = 0;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            turn++;
            if (turn == 1) {
                JsonNode globArgs = mapper.createObjectNode().put("pattern", "*.java");
                JsonNode bashArgs = mapper.createObjectNode().put("command", "mvn compile");
                return Message.assistantWithTools(List.of(
                    new ToolCall("call_g", "glob", globArgs),
                    new ToolCall("call_b", "bash", bashArgs)
                ));
            }
            return Message.assistant("build complete");
        }
    }

    @Test
    void shouldAskOnlyForWriteToolInAskMode() {
        DualToolRegistry registry = new DualToolRegistry();
        AskMixedProvider provider = new AskMixedProvider();
        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work");
        engine.setPermissionMode(PermissionMode.ASK);

        List<ToolCall> checkedCalls = new ArrayList<>();
        engine.setPermissionHandler(call -> {
            checkedCalls.add(call);
            return true;
        });

        engine.run("build it");

        assertThat(checkedCalls).hasSize(1);
        assertThat(checkedCalls.get(0).name()).isEqualTo("bash");
        assertThat(registry.executedCalls).hasSize(2);
    }

    // === AUTO 模式：handler 不被调用 ===

    @Test
    void shouldExecuteAllToolsWithoutChecksInAutoMode() {
        DualToolRegistry registry = new DualToolRegistry();
        AskWriteProvider provider = new AskWriteProvider();
        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work");
        engine.setPermissionMode(PermissionMode.AUTO);

        engine.setPermissionHandler(call -> {
            throw new AssertionError("permissionHandler should not be called in AUTO mode");
        });

        engine.run("whatever");

        assertThat(registry.executedCalls).hasSize(1);
        assertThat(registry.executedCalls.get(0).name()).isEqualTo("bash");
    }

    // === 默认模式 ===

    @Test
    void shouldDefaultToAutoMode() {
        DualToolRegistry registry = new DualToolRegistry();
        AgentEngine engine = new AgentEngine(null, registry, "/tmp/work");

        assertThat(engine.permissionMode()).isEqualTo(PermissionMode.AUTO);
    }

    // === 并行读工具不受 ASK/PLAN 影响 ===

    static class MultiReadProvider implements LLMProvider {
        int turn = 0;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            turn++;
            if (turn == 1) {
                JsonNode a1 = mapper.createObjectNode().put("pattern", "*.java");
                JsonNode a2 = mapper.createObjectNode().put("pattern", "*.xml");
                return Message.assistantWithTools(List.of(
                    new ToolCall("c1", "glob", a1),
                    new ToolCall("c2", "glob", a2)
                ));
            }
            return Message.assistant("found files");
        }
    }

    @Test
    void shouldExecuteParallelReadsInAskModeWithoutConfirmation() {
        DualToolRegistry registry = new DualToolRegistry();
        MultiReadProvider provider = new MultiReadProvider();
        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work");
        engine.setPermissionMode(PermissionMode.ASK);
        engine.setPermissionHandler(call -> {
            throw new AssertionError("should not ask for read tools");
        });

        engine.run("find all");

        assertThat(registry.executedCalls).hasSize(2);
    }

    @Test
    void shouldExecuteParallelReadsInPlanMode() {
        DualToolRegistry registry = new DualToolRegistry();
        MultiReadProvider provider = new MultiReadProvider();
        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work");
        engine.setPermissionMode(PermissionMode.PLAN);

        engine.run("find all");

        assertThat(registry.executedCalls).hasSize(2);
    }
}
