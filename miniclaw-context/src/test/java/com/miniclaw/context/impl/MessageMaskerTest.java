package com.miniclaw.context.impl;

import static org.assertj.core.api.Assertions.assertThat;
import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.Role;
import com.miniclaw.tools.schema.ToolCall;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageMaskerTest {

    // ─── shouldMask ────────────────────────────────────────────────────

    @Test
    void shouldNotMaskUnder12Turns() {
        assertThat(MessageMasker.shouldMask(5)).isFalse();
        assertThat(MessageMasker.shouldMask(12)).isFalse();
    }

    @Test
    void shouldMaskOver12Turns() {
        assertThat(MessageMasker.shouldMask(13)).isTrue();
        assertThat(MessageMasker.shouldMask(30)).isTrue();
    }

    // ─── Tier0: 最近 3 轮全保留 ──────────────────────────────────────

    @Test
    void shouldPreserveRecentTurns() {
        // 15 turns = 15 USER messages, 1 per turn
        List<Message> msgs = buildLinearSession(15);
        var result = MessageMasker.mask(msgs, 15);

        // 最近 3 轮的 USER 消息应该都在
        List<Message> out = result.messages();
        List<String> userContents = out.stream()
            .filter(m -> m.role() == Role.USER)
            .map(Message::content)
            .toList();
        assertThat(userContents).contains("turn 13", "turn 14", "turn 15");
        assertThat(result.tier0Count()).isGreaterThan(0);
    }

    // ─── Tier1: >=500B TOOL 掩码 ──────────────────────────────────────

    @Test
    void shouldMaskLargeToolOutputInTier1() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("system"));
        // turns 1-8 (old, should be Tier1), turn 9 (recent, Tier0)
        for (int t = 1; t <= 9; t++) {
            msgs.add(Message.user("turn " + t));
            msgs.add(Message.assistant("thinking"));
            msgs.add(Message.toolResult("tc" + t, "x".repeat(600))); // >=500B
        }

        var result = MessageMasker.mask(msgs, 9);

        List<Message> out = result.messages();
        // Tier1 TOOL 应该被掩码
        long maskedCount = out.stream()
            .filter(m -> m.role() == Role.TOOL && m.content() != null
                && m.content().contains("[tool output"))
            .count();
        assertThat(maskedCount).isGreaterThan(0);
        assertThat(result.tier1Count()).isGreaterThan(0);
    }

    // ─── Tier1: <500B TOOL 保留 ───────────────────────────────────────

    @Test
    void shouldPreserveSmallToolOutputInTier1() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("system"));
        for (int t = 1; t <= 9; t++) {
            msgs.add(Message.user("turn " + t));
            msgs.add(Message.assistant("ok"));
            msgs.add(Message.toolResult("tc" + t, "small output")); // <500B
        }

        var result = MessageMasker.mask(msgs, 9);

        List<Message> out = result.messages();
        // 小 TOOL 输出应该保留原样
        long preservedCount = out.stream()
            .filter(m -> m.role() == Role.TOOL && "small output".equals(m.content()))
            .count();
        assertThat(preservedCount).isGreaterThan(0);
    }

    // ─── ASSISTANT(tool_calls) Tier1 截断 ─────────────────────────────

    @Test
    void shouldTruncateAssistantWithToolCallsInTier1() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("system"));
        // 12 old turns to push them into Tier1
        for (int t = 1; t <= 15; t++) {
            msgs.add(Message.user("turn " + t));
            msgs.add(new Message(Role.ASSISTANT, "a".repeat(600),
                List.of(new ToolCall("tc" + t, "bash", null)), null));
            msgs.add(Message.toolResult("tc" + t, "ok"));
        }

        var result = MessageMasker.mask(msgs, 15);

        // 至少有一个 ASSISTANT 被截断
        boolean found = result.messages().stream()
            .filter(m -> m.role() == Role.ASSISTANT && m.toolCalls() != null)
            .anyMatch(m -> m.content() != null && m.content().contains("[tool_calls")
                && m.content().contains("..."));
        assertThat(found).isTrue();
    }

    // ─── Tier2: 保留 tool_call 名称 + 截断推理 ────────────────────────

    @Test
    void shouldKeepToolCallNamesAndReasoningInTier2() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("system"));
        // Build 25 turns so turns 1-5 are Tier2
        for (int t = 1; t <= 25; t++) {
            msgs.add(Message.user("turn " + t));
            msgs.add(new Message(Role.ASSISTANT, "I will read the file and search for patterns",
                List.of(new ToolCall("tc" + t, "bash", null)), null));
            msgs.add(Message.toolResult("tc" + t, "x".repeat(600)));
        }

        var result = MessageMasker.mask(msgs, 25);

        // Tier2 TOOL 应该被标记为 elided
        long elidedCount = result.messages().stream()
            .filter(m -> m.role() == Role.TOOL && m.content() != null
                && m.content().contains("elided"))
            .count();
        assertThat(elidedCount).isGreaterThan(0);
    }

    // ─── 纯文本 ASSISTANT Tier2 截断 ──────────────────────────────────

    @Test
    void shouldTruncatePlainAssistantInTier2() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("system"));
        for (int t = 1; t <= 25; t++) {
            msgs.add(Message.user("turn " + t));
            msgs.add(Message.assistant("x".repeat(200)));
            msgs.add(Message.toolResult("tc" + t, "ok"));
        }

        var result = MessageMasker.mask(msgs, 25);

        // 应该有被截断的纯文本 ASSISTANT
        boolean found = result.messages().stream()
            .filter(m -> m.role() == Role.ASSISTANT && (m.toolCalls() == null || m.toolCalls().isEmpty()))
            .anyMatch(m -> m.content() != null && m.content().endsWith("..."));
        assertThat(found).isTrue();
    }

    // ─── 空 TOOL 输出转为占位符（避免 API 400 错误） ────────────────

    @Test
    void shouldReplaceEmptyToolOutputWithPlaceholder() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("system"));
        for (int t = 1; t <= 15; t++) {
            msgs.add(Message.user("turn " + t));
            msgs.add(Message.assistant("ok"));
            msgs.add(Message.toolResult("tc" + t, "")); // empty
        }

        var result = MessageMasker.mask(msgs, 15);

        // 空 TOOL 应转为占位符而非丢弃，以保持 tool_calls→tool 消息配对
        long placeholderCount = result.messages().stream()
            .filter(m -> m.role() == Role.TOOL && m.content() != null
                && m.content().contains("[tool output — empty]"))
            .count();
        assertThat(placeholderCount).isEqualTo(15);
    }

    // ─── SYSTEM 跨层保留 ──────────────────────────────────────────────

    @Test
    void shouldPreserveAllSystemMessages() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("first system"));
        for (int t = 1; t <= 25; t++) {
            msgs.add(Message.user("turn " + t));
            msgs.add(Message.assistant("ok"));
        }

        var result = MessageMasker.mask(msgs, 25);

        long sysCount = result.messages().stream()
            .filter(m -> m.role() == Role.SYSTEM)
            .count();
        assertThat(sysCount).isEqualTo(1);
        assertThat(result.messages().get(0).content()).isEqualTo("first system");
    }

    // ─── 空列表不崩溃 ─────────────────────────────────────────────────

    @Test
    void shouldHandleEmptySession() {
        var result = MessageMasker.mask(List.of(), 10);
        assertThat(result.messages()).isEmpty();
        assertThat(result.tier0Count()).isEqualTo(0);
    }

    // ─── Tier3: 21+ 轮丢弃 ────────────────────────────────────────────

    @Test
    void shouldDiscardMessagesBeyondTier3() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("system"));
        for (int t = 1; t <= 30; t++) {
            msgs.add(Message.user("turn " + t));
            msgs.add(Message.assistant("ok"));
            msgs.add(Message.toolResult("tc" + t, "output"));
        }

        var result = MessageMasker.mask(msgs, 30);

        // Tier3 应该 >0
        assertThat(result.tier3Count()).isGreaterThan(0);
        // 最老的 turn 应该不在了
        List<String> userContents = result.messages().stream()
            .filter(m -> m.role() == Role.USER)
            .map(Message::content)
            .toList();
        assertThat(userContents).doesNotContain("turn 1");
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private static List<Message> buildLinearSession(int nTurns) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("system"));
        for (int t = 1; t <= nTurns; t++) {
            msgs.add(Message.user("turn " + t));
            msgs.add(Message.assistant("thinking turn " + t));
            msgs.add(Message.toolResult("tc" + t, "output " + t));
        }
        return msgs;
    }
}
