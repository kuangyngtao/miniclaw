package com.clawkit.context.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.clawkit.context.ContextManager;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.Role;
import com.clawkit.tools.schema.ToolCall;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LadderedCompactorTest {

    private final ContextManager compactor = new LadderedCompactor(null, new CharFallbackTokenizer());
    private static final int MAX_TOKENS = 1000;

    // === Token 估算 ===

    @Test
    void shouldEstimateTokensForEnglishText() {
        List<Message> messages = List.of(
            Message.user("Hello world, this is a test message.")
        );
        int tokens = compactor.estimateTokens(messages);
        assertThat(tokens).isGreaterThan(0);
        assertThat(tokens).isLessThan(50);
    }

    // === 低 token 不触发压力规则，但常驻规则仍执行 ===

    @Test
    void shouldNotCompactWhenUnderThreshold() {
        List<Message> messages = List.of(
            Message.system("You are a helpful assistant."),
            Message.user("hi"),
            Message.assistant("hello")
        );
        List<Message> result = compactor.compact(messages, MAX_TOKENS);
        // 无 TOOL/ASSISTANT(tool_calls)，常驻规则不修改任何消息
        assertThat(result).hasSize(3);
    }

    // === 压力规则：删空行 + 截断超长行（无 TOOL/tool_calls 时行为不变） ===

    @Test
    void shouldRemoveEmptyLinesAndTruncateLongLines() {
        String longLine = "a".repeat(500);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("You are a helpful assistant."));
        for (int i = 0; i < 5; i++) {
            messages.add(Message.user("question " + i));
            messages.add(Message.assistant("answer " + i + "\n\n\n" + longLine + "\n\nextra"));
        }
        for (int i = 5; i < 8; i++) {
            messages.add(Message.user("recent question " + i));
            messages.add(Message.assistant("recent answer " + i));
        }

        List<Message> result = compactor.compact(messages, 200);

        assertThat(result).isNotEmpty();
        boolean foundTruncated = false;
        for (Message msg : result) {
            if (msg.content() != null) {
                String[] lines = msg.content().split("\n");
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        assertThat(line.length()).isLessThanOrEqualTo(303);
                    }
                }
                if (msg.content().contains("...")) foundTruncated = true;
            }
        }
        assertThat(foundTruncated).isTrue();
    }

    // === Phase 4: TOOL 掩码已移至 MessageMasker，Compactor 不再掩码 ===

    @Test
    void shouldNotMaskToolOutputInCompressibleZone() {
        var args = JsonNodeFactory.instance.objectNode();
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("You are an assistant."));
        for (int i = 0; i < 10; i++) {
            messages.add(Message.user("find files " + i));
            messages.add(Message.assistantWithTools(
                List.of(new ToolCall("call_" + i, "glob", args))));
            messages.add(Message.toolResult("call_" + i, "file1.java\nfile2.java\nfile3.java"));
        }

        List<Message> result = compactor.compact(messages, MAX_TOKENS);

        // Phase 4: TOOL 掩码已移至 MessageMasker，
        // LadderedCompactor 不再对 TOOL 输出做掩码处理
        long maskedCount = result.stream()
            .filter(m -> m.role() == Role.TOOL)
            .filter(m -> m.content() != null && m.content().matches("\\[tool:glob output — \\d+ bytes\\]"))
            .count();
        assertThat(maskedCount).isZero();

        // 不应有跨轮去重文本（旧 L2 行为已移除）
        boolean hasDedupMarker = result.stream()
            .anyMatch(m -> m.content() != null && m.content().contains("content unchanged"));
        assertThat(hasDedupMarker).isFalse();
    }

    // === 常驻规则：ASSISTANT(tool_calls) 保留 ===

    @Test
    void shouldPreserveAssistantWithToolCalls() {
        var args = JsonNodeFactory.instance.objectNode();
        ToolCall tc = new ToolCall("call_1", "bash", args);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("You are an assistant."));
        // 在压缩区放带 tool_calls 的 ASSISTANT
        for (int i = 0; i < 6; i++) {
            messages.add(Message.user("task " + i));
            messages.add(Message.assistantWithTools(List.of(tc)));
            messages.add(Message.toolResult("call_1", "some output"));
        }

        List<Message> result = compactor.compact(messages, 150);

        // 压缩区 ASSISTANT(tool_calls) 应完整保留
        boolean assistantPreserved = result.stream()
            .filter(m -> m.role() == Role.ASSISTANT)
            .filter(m -> m.toolCalls() != null && !m.toolCalls().isEmpty())
            .allMatch(m -> m.toolCalls().get(0).name().equals("bash"));
        assertThat(assistantPreserved).isTrue();
    }

    // === 保护区：最近 3 轮 + system 未被压力压缩 ===

    @Test
    void shouldProtectRecentTurns() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("You are a coding assistant."));
        for (int i = 1; i <= 10; i++) {
            messages.add(Message.user("question " + i));
            messages.add(Message.assistant("answer " + i));
            messages.add(Message.toolResult("call_" + i, "tool output " + i));
        }

        List<Message> result = compactor.compact(messages, 300);

        // system 消息应保留
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(0).content()).contains("coding assistant");

        // 保护区内最后一条 TOOL 消息应保留原样（短内容 < 1000 字符）
        Message last = result.get(result.size() - 1);
        assertThat(last.role()).isEqualTo(Role.TOOL);
        assertThat(last.content()).contains("tool output 10");
    }

    // === 保护区：TOOL >1000 字符掐头去尾 ===

    @Test
    void shouldHeadTailTruncateLargeProtectionZoneTool() {
        String longContent = "HEAD:" + "A".repeat(600) + ":MIDDLE:" + "B".repeat(600) + ":TAIL";
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("You are an assistant."));
        messages.add(Message.user("read file"));
        messages.add(Message.assistant("reading..."));
        messages.add(Message.toolResult("call_1", longContent));

        List<Message> result = compactor.compact(messages, MAX_TOKENS);

        Message toolMsg = result.stream()
            .filter(m -> m.role() == Role.TOOL)
            .findFirst().orElseThrow();

        assertThat(toolMsg.content()).startsWith("HEAD:");
        assertThat(toolMsg.content()).contains("…[truncated");
        assertThat(toolMsg.content()).endsWith(":TAIL");
        // 头部保留前 500 字符
        assertThat(toolMsg.content().substring(0, 500)).isEqualTo(longContent.substring(0, 500));
        // 尾部保留后 500 字符
        assertThat(toolMsg.content()).endsWith(longContent.substring(longContent.length() - 500));
    }

    // === 保护区：TOOL ≤1000 字符不动 ===

    @Test
    void shouldNotTruncateProtectionZoneToolUnderThreshold() {
        String shortContent = "A".repeat(1000);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("You are an assistant."));
        messages.add(Message.user("read file"));
        messages.add(Message.assistant("reading..."));
        messages.add(Message.toolResult("call_1", shortContent));

        List<Message> result = compactor.compact(messages, MAX_TOKENS);

        Message toolMsg = result.stream()
            .filter(m -> m.role() == Role.TOOL)
            .findFirst().orElseThrow();
        assertThat(toolMsg.content()).isEqualTo(shortContent);
    }

    // === 60% 以下不触发压力规则 ===

    @Test
    void shouldNotApplyPressureRulesWhenUsageBelowThreshold() {
        // 全部短消息 + 高 MAX_TOKENS → usage < 60%
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("You are an assistant."));
        messages.add(Message.user("question"));
        messages.add(Message.assistant("answer\n\n\n" + "a".repeat(400) + "\n\nextra\n\n"));

        List<Message> result = compactor.compact(messages, 2000);

        // usage < 60%，不触发压力规则 → 长行应保持原样（未被截断）
        boolean hasLongLine = false;
        for (Message msg : result) {
            if (msg.content() != null) {
                for (String line : msg.content().split("\n")) {
                    if (line.length() > 300) {
                        hasLongLine = true;
                        break;
                    }
                }
            }
        }
        assertThat(hasLongLine).isTrue();
    }

    // === 错误信息不受低信号过滤影响 ===

    @Test
    void shouldPreserveErrorMessages() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("You are an assistant."));
        for (int i = 0; i < 6; i++) {
            messages.add(Message.user("task " + i));
            messages.add(Message.assistant("working..."));
        }
        messages.add(3, Message.assistant(
            "NullPointerException at AgentEngine.java:91\n"
            + "Caused by: java.lang.IllegalStateException"));

        List<Message> result = compactor.compact(messages, 200);

        boolean hasError = result.stream()
            .filter(m -> m.role() == Role.ASSISTANT)
            .anyMatch(m -> m.content() != null && m.content().contains("NullPointerException"));
        assertThat(hasError).isTrue();
    }

    // Phase 4: TOOL 掩码已移至 MessageMasker，不再由 Compactor 处理

    @Test
    void shouldNotMaskToolOutputWithUnknownToolName() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("You are an assistant."));
        for (int i = 0; i < 8; i++) {
            messages.add(Message.user("task " + i));
            messages.add(Message.assistant("doing..."));
            messages.add(Message.toolResult("orphan_call_" + i, "some output content here"));
        }

        List<Message> result = compactor.compact(messages, 150);

        // Phase 4: Compactor 不再掩码 TOOL
        long unknownCount = result.stream()
            .filter(m -> m.role() == Role.TOOL)
            .filter(m -> m.content() != null && m.content().contains("[tool:unknown"))
            .count();
        assertThat(unknownCount).isZero();
    }

    // === ToolCall ID 保留在掩码后的 TOOL 消息中 ===

    @Test
    void shouldPreserveToolCallIdAfterMasking() {
        var args = JsonNodeFactory.instance.objectNode();
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("You are an assistant."));
        for (int i = 0; i < 6; i++) {
            messages.add(Message.user("task " + i));
            messages.add(Message.assistantWithTools(
                List.of(new ToolCall("call_" + i, "read", args))));
            messages.add(Message.toolResult("call_" + i, "content " + i));
        }

        List<Message> result = compactor.compact(messages, 150);

        // 掩码后 toolCallId 应保留
        boolean allHaveToolCallId = result.stream()
            .filter(m -> m.role() == Role.TOOL)
            .allMatch(m -> m.toolCallId() != null && m.toolCallId().startsWith("call_"));
        assertThat(allHaveToolCallId).isTrue();
    }
}
