package com.clawkit.context.impl;

import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.Role;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色+时效双维上下文掩码。
 * 在 LadderedCompactor 之前运行，按轮次距离分层，按角色差异化保留/截断/丢弃。
 */
public final class MessageMasker {

    private static final int T0_TURNS = 3;       // Tier0: 最近 3 轮全可见
    private static final int T1_TURNS = 10;      // Tier1: 4-10 轮
    private static final int T2_TURNS = 20;      // Tier2: 11-20 轮
    private static final int TRIGGER_TURNS = 0;  // 始终启用掩码（Phase 4: unified masking）

    private static final int ASSISTANT_CUT_TOOL = 500;   // ASSISTANT(tool_calls) Tier1 截断
    private static final int ASSISTANT_CUT_PLAIN = 300;  // ASSISTANT(纯文本) Tier1 截断
    private static final int ASSISTANT_CUT_T2_TOOL = 200;  // ASSISTANT(tool_calls) Tier2 截断
    private static final int ASSISTANT_CUT_T2_PLAIN = 150; // ASSISTANT(纯文本) Tier2 截断
    private static final int TOOL_SMALL_BYTES = 500;    // 小输出阈值

    private MessageMasker() {}

    public record MaskedContext(
        List<Message> messages,
        int tier0Count, int tier1Count, int tier2Count, int tier3Count,
        List<TurnGroup> evictedTurnGroups
    ) {
        public MaskedContext(List<Message> messages, int t0, int t1, int t2, int t3) {
            this(messages, t0, t1, t2, t3, List.of());
        }
    }

    public static boolean shouldMask(int turnCount) {
        return turnCount > TRIGGER_TURNS;
    }

    public static MaskedContext mask(List<Message> messages, int currentTurn) {
        if (messages.isEmpty()) {
            return new MaskedContext(List.of(), 0, 0, 0, 0);
        }

        // 1. 扫描消息列表，按 USER 消息分配轮次编号
        int[] turnMap = new int[messages.size()];
        int turn = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).role() == Role.USER) {
                turn++;
            }
            turnMap[i] = turn;
        }
        int totalTurns = turn;

        // 2. 按 Tier 处理每条消息
        List<Message> result = new ArrayList<>(messages.size());
        Map<Integer, List<Message>> evictionGroups = new HashMap<>();
        int t0 = 0, t1 = 0, t2 = 0, t3 = 0;

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            int msgTurn = turnMap[i];

            if (msg.role() == Role.SYSTEM) {
                result.add(msg);
                continue;
            }

            int tier = classifyTier(msgTurn, totalTurns);

            switch (tier) {
                case 0 -> { t0++; result.add(handleTier0(msg)); }
                case 1 -> { t1++; result.add(handleTier1(msg)); }
                case 2 -> { t2++; result.add(handleTier2(msg)); }
                case 3 -> { t3++; evictionGroups.computeIfAbsent(msgTurn, k -> new ArrayList<>()).add(msg); }
            }
        }

        // 过滤 null（Tier3 已改为软驱逐，此处保留兼容）
        List<Message> filtered = new ArrayList<>(result.size());
        for (Message m : result) {
            if (m != null) filtered.add(m);
        }

        List<TurnGroup> evicted = TurnGroup.fromEvictionMap(evictionGroups);
        return new MaskedContext(filtered, t0, t1, t2, t3, evicted);
    }

    private static int classifyTier(int msgTurn, int totalTurns) {
        int distance = totalTurns - msgTurn;
        if (distance < T0_TURNS) return 0;
        if (distance < T1_TURNS) return 1;
        if (distance < T2_TURNS) return 2;
        return 3;
    }

    // ── Tier0: 全保留（仅对特大 TOOL 掐头去尾） ──

    private static Message handleTier0(Message msg) {
        if (msg.role() == Role.TOOL) {
            if (msg.content() == null || msg.content().isBlank())
                return new Message(Role.TOOL, "[tool output — empty]", null, msg.toolCallId());
            if (msg.content().length() > 1000) {
                String content = msg.content();
                int len = content.length();
                return new Message(Role.TOOL,
                    content.substring(0, 500)
                    + "\n…[truncated " + (len - 1000) + " bytes]…\n"
                    + content.substring(len - 500),
                    null, msg.toolCallId());
            }
        }
        return msg;
    }

    // ── Tier1: TOOL 掩码 / ASSISTANT 截断 ──

    private static Message handleTier1(Message msg) {
        return switch (msg.role()) {
            case USER -> msg;
            case TOOL -> maskToolTier1(msg);
            case ASSISTANT -> truncateAssistant(msg, ASSISTANT_CUT_TOOL, ASSISTANT_CUT_PLAIN);
            default -> msg;
        };
    }

    private static Message maskToolTier1(Message msg) {
        String content = msg.content();
        if (content == null || content.isBlank())
            return new Message(Role.TOOL, "[tool output — empty]", null, msg.toolCallId());
        int len = content.length();
        if (len < TOOL_SMALL_BYTES) return msg; // 小输出全保留
        return new Message(Role.TOOL,
            "[tool output — " + len + " bytes]",
            null, msg.toolCallId());
    }

    // ── Tier2: 仅保留骨架 ──

    private static Message handleTier2(Message msg) {
        return switch (msg.role()) {
            case USER -> msg;
            case TOOL -> maskToolTier2(msg);
            case ASSISTANT -> truncateAssistant(msg, ASSISTANT_CUT_T2_TOOL, ASSISTANT_CUT_T2_PLAIN);
            default -> msg;
        };
    }

    private static Message maskToolTier2(Message msg) {
        String content = msg.content();
        if (content == null || content.isBlank())
            return new Message(Role.TOOL, "[tool output — empty]", null, msg.toolCallId());
        if (content.length() < TOOL_SMALL_BYTES) return msg; // 小输出保留
        return new Message(Role.TOOL,
            "[tool output — elided]",
            null, msg.toolCallId());
    }

    // ── ASSISTANT 截断 ──

    private static Message truncateAssistant(Message msg, int cutWithTools, int cutPlain) {
        boolean hasToolCalls = msg.toolCalls() != null && !msg.toolCalls().isEmpty();

        if (hasToolCalls) {
            // 保留 tool_call 名称列表 + 截断正文
            String names = msg.toolCalls().stream()
                .map(tc -> tc.name())
                .reduce((a, b) -> a + ", " + b).orElse("");
            String prefix = "[tool_calls: " + names + "] ";

            if (msg.content() == null || msg.content().isBlank()) {
                return new Message(msg.role(), prefix, msg.toolCalls(), msg.toolCallId());
            }
            String truncated = msg.content().length() > cutWithTools
                ? msg.content().substring(0, cutWithTools) + "..."
                : msg.content();
            return new Message(msg.role(), prefix + truncated, msg.toolCalls(), msg.toolCallId());
        }

        // 纯文本 ASSISTANT
        if (msg.content() == null || msg.content().isBlank()) return msg;
        if (msg.content().length() > cutPlain) {
            return new Message(msg.role(),
                msg.content().substring(0, cutPlain) + "...",
                null, msg.toolCallId());
        }
        return msg;
    }
}
