package com.miniclaw.context.impl;

import com.miniclaw.context.ContextManager;
import com.miniclaw.context.Summarizer;
import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.Role;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        int currentTokens = estimateTokens(messages);
        double usage = (double) currentTokens / maxTokens;

        if (usage < 0.6) {
            return new ArrayList<>(messages);
        }

        int recentBoundary = findRecentTurnsBoundary(messages);
        List<Message> result = new ArrayList<>();

        // 保护区 (recent N turns + system): 不压缩
        // 压缩区 (older turns): 阶梯压缩

        if (usage < 0.8) {
            result = compactL1(messages, recentBoundary);
        } else {
            result = compactL2(messages, recentBoundary);
        }

        int afterTokens = estimateTokens(result);
        int reductionPct = (int) (100.0 * (currentTokens - afterTokens) / currentTokens);
        log.info("[Compactor] 压缩完成: {} → {} tokens (减少 {}%)",
            currentTokens, afterTokens, reductionPct);

        if ((double) afterTokens / maxTokens > 0.95 && summarizer != null) {
            List<Message> l3 = compactL3(result, recentBoundary);
            if (l3 != null) {
                int l3Tokens = estimateTokens(l3);
                log.info("[Compactor] L3 LLM 摘要完成: {} → {} tokens", afterTokens, l3Tokens);
                return l3;
            }
        }

        if ((double) afterTokens / maxTokens > 0.95) {
            log.info("[Compactor] Token 仍超限 ({} tokens)，L3 未执行 (summarizer={})",
                afterTokens, summarizer != null ? "已配置但失败" : "未配置");
        }

        return result;
    }

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

    // === L1: 删空行 + 截断超长行 ===
    private List<Message> compactL1(List<Message> messages, int recentBoundary) {
        List<Message> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (i >= recentBoundary || msg.role() == Role.SYSTEM) {
                result.add(msg);
                continue;
            }
            result.add(compressContentL1(msg));
        }
        return result;
    }

    private Message compressContentL1(Message msg) {
        String content = msg.content();
        if (content == null || content.isEmpty()) return msg;

        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.stripTrailing();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() > MAX_LINE_LENGTH) {
                sb.append(trimmed.substring(0, MAX_LINE_LENGTH)).append("...\n");
            } else {
                sb.append(trimmed).append('\n');
            }
        }
        return new Message(msg.role(), sb.toString().stripTrailing(), msg.toolCalls(), msg.toolCallId());
    }

    // === L2: 跨轮去重 + 删低信号行 ===
    private List<Message> compactL2(List<Message> messages, int recentBoundary) {
        List<Message> result = new ArrayList<>();
        Set<String> contentHashes = new HashSet<>();

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (i >= recentBoundary || msg.role() == Role.SYSTEM) {
                result.add(msg);
                continue;
            }

            if (msg.role() == Role.TOOL && msg.content() != null) {
                String hash = hash8(msg.content());
                if (!contentHashes.add(hash)) {
                    // 跨轮重复：替换为简短提示
                    int savedBytes = msg.content().length();
                    result.add(new Message(Role.TOOL,
                        "[content unchanged from earlier turn — " + savedBytes + " bytes omitted]",
                        null, msg.toolCallId()));
                    continue;
                }
            }

            result.add(compressContentL2(msg));
        }
        return result;
    }

    private Message compressContentL2(Message msg) {
        String content = msg.content();
        if (content == null || content.isEmpty()) return msg;

        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.stripTrailing();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() > MAX_LINE_LENGTH) {
                sb.append(trimmed.substring(0, MAX_LINE_LENGTH)).append("...\n");
                continue;
            }
            // 跳过低信号行
            if (isLowSignal(trimmed)) continue;
            sb.append(trimmed).append('\n');
        }

        String result = sb.toString().stripTrailing();
        if (result.isEmpty() && msg.role() == Role.TOOL) {
            return new Message(Role.TOOL, "[low-signal output removed]", null, msg.toolCallId());
        }
        return new Message(msg.role(), result, msg.toolCalls(), msg.toolCallId());
    }

    // === L3: LLM 摘要 ===
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
        // 保留 system prompt（如果存在）
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

    private boolean isLowSignal(String line) {
        String lower = line.stripLeading().toLowerCase();
        for (String prefix : LOW_SIGNAL_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        if (TRUNCATED_MARKER.matcher(line).find()) return true;
        if (SEPARATOR_LINE.matcher(line).matches()) return true;
        return false;
    }

    private static String hash8(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(content.hashCode());
        }
    }
}
