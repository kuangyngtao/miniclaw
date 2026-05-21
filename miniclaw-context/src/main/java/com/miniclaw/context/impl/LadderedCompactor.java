package com.miniclaw.context.impl;

import com.miniclaw.context.ContextManager;
import com.miniclaw.context.Summarizer;
import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.Role;
import com.miniclaw.tools.schema.ToolCall;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LadderedCompactor implements ContextManager {

    private static final Logger log = LoggerFactory.getLogger(LadderedCompactor.class);

    private static final int CHARS_PER_TOKEN_EN = 3;
    private static final int CHARS_PER_TOKEN_CN = 4;
    private static final int MAX_LINE_LENGTH = 300;
    private static final int RECENT_TURNS = 3;
    private static final int HEAD_TAIL_THRESHOLD = 1000;
    private static final int HEAD_TAIL_KEEP = 500;
    private static final double PRESSURE_THRESHOLD = 0.6;
    private static final double L3_THRESHOLD = 0.9;

    private static final Set<String> LOW_SIGNAL_PREFIXES = Set.of(
        "executing...", "reading file...", "done.", "running...", "processing..."
    );

    private static final Pattern SEPARATOR_LINE = Pattern.compile("^[-=*_]{20,}$");
    private static final Pattern TRUNCATED_MARKER = Pattern.compile("\\[output truncated at", Pattern.CASE_INSENSITIVE);

    private final Summarizer summarizer;

    public LadderedCompactor() {
        this.summarizer = null;
    }

    public LadderedCompactor(Summarizer summarizer) {
        this.summarizer = summarizer;
    }

    @Override
    public int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message msg : messages) {
            String content = msg.content();
            if (content == null) continue;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c >= 0x4e00 && c <= 0x9fff) {
                    total++;
                    if (total % CHARS_PER_TOKEN_CN == 0) continue;
                    if (i + 1 < content.length()) {
                        char next = content.charAt(i + 1);
                        if (next >= 0x4e00 && next <= 0x9fff) continue;
                    }
                }
            }
            total += content.length() / CHARS_PER_TOKEN_EN;
        }
        return total;
    }

    @Override
    public List<Message> compact(List<Message> messages, int maxTokens) {
        Map<String, String> toolNameIndex = buildToolNameIndex(messages);
        int recentBoundary = findRecentTurnsBoundary(messages);

        List<Message> result = applyAlwaysOnRules(messages, recentBoundary, toolNameIndex);

        int currentTokens = estimateTokens(result);
        double usage = (double) currentTokens / maxTokens;

        if (usage < PRESSURE_THRESHOLD) {
            return result;
        }

        int beforePressure = currentTokens;
        result = applyPressureRules(result, recentBoundary);
        int afterTokens = estimateTokens(result);
        int reductionPct = (int) (100.0 * (beforePressure - afterTokens) / beforePressure);
        log.info("[Compactor] 压力压缩: {} → {} tokens (减少 {}%)",
            beforePressure, afterTokens, reductionPct);

        if ((double) afterTokens / maxTokens > L3_THRESHOLD && summarizer != null) {
            List<Message> l3 = compactL3(result, recentBoundary);
            if (l3 != null) {
                int l3Tokens = estimateTokens(l3);
                log.info("[Compactor] L3 LLM 摘要: {} → {} tokens", afterTokens, l3Tokens);
                return l3;
            }
        }

        if ((double) afterTokens / maxTokens > L3_THRESHOLD) {
            log.info("[Compactor] Token 仍超限 ({} tokens), L3 未执行 (summarizer={})",
                afterTokens, summarizer != null ? "已配置但失败" : "未配置");
        }

        return result;
    }

    // === 工具名索引：toolCallId → toolName ===

    private Map<String, String> buildToolNameIndex(List<Message> messages) {
        Map<String, String> index = new HashMap<>();
        for (Message msg : messages) {
            if (msg.role() == Role.ASSISTANT && msg.toolCalls() != null) {
                for (ToolCall tc : msg.toolCalls()) {
                    if (tc.id() != null) {
                        index.put(tc.id(), tc.name());
                    }
                }
            }
        }
        return index;
    }

    // === 常驻规则：每次 compact() 都执行，不依赖 token 阈值 ===

    private List<Message> applyAlwaysOnRules(List<Message> messages, int recentBoundary,
                                             Map<String, String> toolNameIndex) {
        List<Message> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            boolean inProtectionZone = i >= recentBoundary || msg.role() == Role.SYSTEM;

            if (inProtectionZone) {
                result.add(applyProtectionZoneRules(msg));
            } else {
                result.add(applyCompressibleZoneAlwaysOn(msg, toolNameIndex));
            }
        }
        return result;
    }

    private Message applyProtectionZoneRules(Message msg) {
        if (msg.role() == Role.TOOL && msg.content() != null && msg.content().length() > HEAD_TAIL_THRESHOLD) {
            return headTailTruncate(msg);
        }
        return msg;
    }

    private Message applyCompressibleZoneAlwaysOn(Message msg, Map<String, String> toolNameIndex) {
        if (msg.role() == Role.TOOL) {
            String toolName = toolNameIndex.getOrDefault(msg.toolCallId(), "unknown");
            return maskToolOutput(msg, toolName);
        }
        if (msg.role() == Role.ASSISTANT && msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
            return msg; // 保留逻辑链
        }
        return msg;
    }

    // === 压力规则：token 紧张时触发 ===

    private List<Message> applyPressureRules(List<Message> messages, int recentBoundary) {
        List<Message> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            boolean inProtectionZone = i >= recentBoundary || msg.role() == Role.SYSTEM;

            if (inProtectionZone) {
                result.add(msg);
            } else if (msg.role() == Role.TOOL) {
                result.add(msg); // 常驻已掩码
            } else if (msg.role() == Role.ASSISTANT && msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                result.add(msg); // 常驻已保留
            } else {
                result.add(compressLineLevel(msg));
            }
        }
        return result;
    }

    private Message compressLineLevel(Message msg) {
        String content = msg.content();
        if (content == null || content.isEmpty()) return msg;

        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.stripTrailing();
            if (trimmed.isEmpty()) continue;
            if (isLowSignal(trimmed)) continue;
            if (trimmed.length() > MAX_LINE_LENGTH) {
                sb.append(trimmed, 0, MAX_LINE_LENGTH).append("...\n");
            } else {
                sb.append(trimmed).append('\n');
            }
        }

        String resultStr = sb.toString().stripTrailing();
        if (resultStr.isEmpty()) {
            return new Message(msg.role(), "[empty after compression]", msg.toolCalls(), msg.toolCallId());
        }
        return new Message(msg.role(), resultStr, msg.toolCalls(), msg.toolCallId());
    }

    // === 掩码与截断 ===

    private Message maskToolOutput(Message msg, String toolName) {
        int len = msg.content() != null ? msg.content().length() : 0;
        return new Message(Role.TOOL,
            "[tool:" + toolName + " output — " + len + " bytes]",
            null, msg.toolCallId());
    }

    private Message headTailTruncate(Message msg) {
        String content = msg.content();
        int len = content.length();
        int truncated = len - HEAD_TAIL_KEEP * 2;
        if (truncated <= 0) return msg;
        String newContent = content.substring(0, HEAD_TAIL_KEEP)
            + "\n…[truncated " + truncated + " bytes]…\n"
            + content.substring(len - HEAD_TAIL_KEEP);
        return new Message(Role.TOOL, newContent, null, msg.toolCallId());
    }

    // === 保护区边界 ===

    private int findRecentTurnsBoundary(List<Message> messages) {
        int userCount = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                userCount++;
                if (userCount >= RECENT_TURNS) {
                    return i;
                }
            }
        }
        return 0;
    }

    // === L3: LLM 摘要（方法体不变） ===

    private List<Message> compactL3(List<Message> messages, int recentBoundary) {
        if (recentBoundary == 0) return null;

        List<Message> oldMessages = new ArrayList<>();
        for (int i = 0; i < recentBoundary; i++) {
            Message msg = messages.get(i);
            if (msg.role() == Role.SYSTEM) continue;
            oldMessages.add(msg);
        }
        if (oldMessages.isEmpty()) return null;

        List<Message> summarizationContext = new ArrayList<>();
        summarizationContext.add(Message.system(
            "你是一个对话摘要器。请将以下 AI 编程助手与用户的对话压缩为一段中文摘要（200-400字）。\n"
            + "保留：关键决策、重要发现、错误和修复、文件路径、函数名、技术细节。\n"
            + "忽略：问候语、重复内容、无意义的输出行。\n"
            + "只输出摘要文本，不要加任何前缀或后缀。"));
        summarizationContext.addAll(oldMessages);
        summarizationContext.add(Message.user("请总结以上对话，只输出摘要。"));

        String summary;
        try {
            summary = summarizer.summarize(summarizationContext);
        } catch (Exception e) {
            log.warn("[Compactor] L3 LLM 摘要调用失败: {}", e.getMessage());
            return null;
        }

        if (summary == null || summary.isBlank()) return null;

        List<Message> result = new ArrayList<>();
        for (Message msg : messages) {
            if (msg.role() == Role.SYSTEM) {
                result.add(msg);
                break;
            }
        }
        result.add(Message.system("[Conversation Summary] " + summary.strip()));
        result.addAll(messages.subList(recentBoundary, messages.size()));
        return result;
    }

    // === 低信号判断 ===

    private boolean isLowSignal(String line) {
        String lower = line.stripLeading().toLowerCase();
        for (String prefix : LOW_SIGNAL_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        if (TRUNCATED_MARKER.matcher(line).find()) return true;
        if (SEPARATOR_LINE.matcher(line).matches()) return true;
        return false;
    }
}
