package com.miniclaw.context.impl;

import com.miniclaw.context.ContextManager;
import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.Role;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LadderedCompactorTest {

    private final ContextManager compactor = new LadderedCompactor();
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

    // === 低 token 不压缩 ===

    @Test
    void shouldNotCompactWhenUnderThreshold() {
        List<Message> messages = List.of(
            Message.system("You are a helpful assistant."),
            Message.user("hi"),
            Message.assistant("hello")
        );
        List<Message> result = compactor.compact(messages, MAX_TOKENS);
        assertThat(result).hasSize(3);
    }

    // === L1: 删空行 + 截断超长行 ===

    @Test
    void shouldRemoveEmptyLinesAndTruncateLongLines() {
        String longLine = "a".repeat(500);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("You are a helpful assistant."));
        // 前 5 轮在压缩区，放长行；后 3 轮在保护区，放短内容
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
        // 压缩区的超长行应被截断
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

    // === L2: 跨轮去重 ===

    @Test
    void shouldDedupeIdenticalToolResults() {
        String dupContent = "file1.java\nfile2.java\nfile3.java";
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("You are an assistant."));
        for (int i = 0; i < 10; i++) {
            messages.add(Message.user("find files " + i));
            messages.add(Message.assistant("searching..."));
            messages.add(Message.toolResult("call_" + i, dupContent));
        }

        List<Message> result = compactor.compact(messages, 150);

        // 应有至少一条去重替换消息
        long dedupCount = result.stream()
            .filter(m -> m.content() != null && m.content().contains("content unchanged"))
            .count();
        assertThat(dedupCount).isGreaterThan(0);
    }

    // === 保护区：最近 3 轮 + system 消息 ===

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

        // 最后一条消息应该是 tool_result for call_10
        Message last = result.get(result.size() - 1);
        assertThat(last.role()).isEqualTo(Role.TOOL);
        assertThat(last.content()).contains("tool output 10");
    }

    // === 错误信息保护 ===

    @Test
    void shouldPreserveErrorMessages() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("You are an assistant."));
        for (int i = 0; i < 6; i++) {
            messages.add(Message.user("task " + i));
            messages.add(Message.assistant("working..."));
        }
        // 插入一条错误消息（在可压缩区）
        messages.add(3, Message.assistant(
            "NullPointerException at AgentEngine.java:91\n"
            + "Caused by: java.lang.IllegalStateException"));

        List<Message> result = compactor.compact(messages, 200);

        // 错误行应该被保留
        boolean hasError = result.stream()
            .filter(m -> m.role() == Role.ASSISTANT)
            .anyMatch(m -> m.content() != null && m.content().contains("NullPointerException"));
        assertThat(hasError).isTrue();
    }
}
