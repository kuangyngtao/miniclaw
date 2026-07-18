package com.clawkit.engine;

import com.clawkit.engine.impl.FileSessionStore;
import com.clawkit.memory.impl.KeywordScorer;
import com.clawkit.provider.LLMProvider;
import com.clawkit.tools.schema.Message;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session persistence facade. PR-12: 依赖 SessionStore 接口。
 */
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private static final String SUMMARY_PROMPT =
        "你是一个对话摘要器。请将以下 AI 编程助手与用户的对话压缩为一段中文摘要（50-150字）。\n"
        + "保留：关键决策、重要发现、错误和修复、文件路径、函数名、技术细节。\n"
        + "忽略：问候语、重复内容、无意义的输出行。\n"
        + "只输出摘要文本，不要加任何前缀或后缀。";

    private static final List<com.clawkit.tools.schema.ToolDefinition> EMPTY_TOOLS = List.of();

    private final SessionStore store;
    private ProviderGateway gateway;

    /** PR-12: 新构造器（依赖接口） */
    public SessionService(SessionStore store) {
        this.store = store;
    }

    /** @deprecated 使用 SessionService(SessionStore) */
    @Deprecated
    public SessionService(Path sessionsDir, LLMProvider provider) {
        this.store = new FileSessionStore(sessionsDir);
    }

    public void setProviderGateway(ProviderGateway gateway) {
        this.gateway = gateway;
    }

    public SessionMeta save(String name, List<Message> messages) {
        String id = java.util.UUID.randomUUID().toString().substring(0, 8);
        String summary = generateSummary(messages);
        Instant now = Instant.now();
        var doc = new SessionDocument(SessionStore.SCHEMA_VERSION, id, name, now, now,
            messages, java.util.Map.of("summary", summary != null ? summary : ""));
        store.save(doc);
        return new SessionMeta(id, name, now, now, messages.size(),
            extractFirstMessage(messages), summary);
    }

    public List<Message> load(String id) {
        return store.load(id)
            .map(SessionDocument::messages)
            .orElseThrow(() -> new SessionStoreException(SessionError.NOT_FOUND, id,
                "[H-003] Session not found: " + id));
    }

    public List<SessionMeta> list() {
        return store.list();
    }

    public void delete(String id) {
        store.delete(id);
    }

    private static String extractFirstMessage(List<Message> messages) {
        for (Message m : messages) {
            if (m.role() == com.clawkit.tools.schema.Role.USER
                && m.content() != null && !m.content().isBlank()) {
                String c = m.content().strip();
                return c.length() > 120 ? c.substring(0, 120) + "..." : c;
            }
        }
        return "";
    }

    // ── stats & prune ──

    public record AgeBucket(String label, int count, long bytes) {}

    public List<AgeBucket> stats() {
        Instant now = Instant.now();
        long[] bytes = {0, 0, 0, 0};
        int[] counts = {0, 0, 0, 0};
        String[] labels = {"< 7 days", "7 - 30 days", "30 - 90 days", "> 90 days"};

        for (SessionMeta m : store.list()) {
            long days = ChronoUnit.DAYS.between(m.updatedAt(), now);
            long size = store.fileSize(m.id());
            if (days < 7) {
                counts[0]++; bytes[0] += size;
            } else if (days < 30) {
                counts[1]++; bytes[1] += size;
            } else if (days < 90) {
                counts[2]++; bytes[2] += size;
            } else {
                counts[3]++; bytes[3] += size;
            }
        }

        List<AgeBucket> buckets = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            if (counts[i] > 0) {
                buckets.add(new AgeBucket(labels[i], counts[i], bytes[i]));
            }
        }
        return buckets;
    }

    public int prune(int olderThanDays) {
        Instant cutoff = Instant.now().minus(olderThanDays, ChronoUnit.DAYS);
        List<SessionMeta> all = store.list();
        int count = 0;
        for (SessionMeta m : all) {
            if (m.updatedAt().isBefore(cutoff)) {
                store.delete(m.id());
                count++;
            }
        }
        log.info("[Session] pruned {} sessions older than {} days", count, olderThanDays);
        return count;
    }

    public List<SessionMeta> search(String query) {
        List<SessionMeta> all = store.list();
        if (all.isEmpty()) return List.of();

        // Build corpus from session search texts for IDF computation
        List<String> corpus = all.stream()
            .map(SessionService::toSearchText)
            .toList();
        KeywordScorer scorer = new KeywordScorer(corpus);

        return all.stream()
            .map(m -> new ScoredSession(m, scorer.score(query, toSearchText(m))))
            .filter(s -> s.score > 0)
            .sorted(Comparator.comparingDouble(ScoredSession::score).reversed())
            .limit(5)
            .map(ScoredSession::meta)
            .toList();
    }

    private record ScoredSession(SessionMeta meta, double score) {}

    private static String toSearchText(SessionMeta m) {
        StringBuilder sb = new StringBuilder();
        if (m.name() != null) sb.append(m.name()).append(' ');
        if (m.firstUserMessage() != null) sb.append(m.firstUserMessage()).append(' ');
        if (m.summary() != null) sb.append(m.summary());
        return sb.toString().strip();
    }

    private String generateSummary(List<Message> messages) {
        try {
            Message result;
            if (gateway != null) {
                var req = com.clawkit.provider.ModelRequest.of(
                    List.of(Message.system(SUMMARY_PROMPT),
                        Message.user("请为以下对话生成摘要：\n" + formatMessagesForSummary(messages))),
                    EMPTY_TOOLS);
                var resp = gateway.generate(req,
                    new com.clawkit.engine.RunScope("session-summary", null, 0,
                        com.clawkit.engine.RunPhase.MEMORY_EXTRACT, null));
                result = resp.hasToolCalls()
                    ? Message.assistantWithTools(resp.toolCalls())
                    : Message.assistant(resp.content() != null ? resp.content() : "");
            } else {
                return fallbackSummary(messages);
            }
            String summary = result.content() != null ? result.content().trim() : "";
            if (!summary.isEmpty()) return summary;
        } catch (Exception e) {
            log.warn("LLM summary failed, using fallback: {}", e.getMessage());
        }
        return fallbackSummary(messages);
    }

    private static String fallbackSummary(List<Message> messages) {
        for (Message msg : messages) {
            if (msg.role() == com.clawkit.tools.schema.Role.USER
                && msg.content() != null && !msg.content().isBlank()) {
                String c = msg.content().strip();
                return c.length() > 80 ? c.substring(0, 80) + "..." : c;
            }
        }
        return "(empty session)";
    }

    private static String formatMessagesForSummary(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Message msg : messages) {
            if (msg.role() == com.clawkit.tools.schema.Role.SYSTEM) continue;
            String role = msg.role().name().toLowerCase();
            String content = msg.content();
            if (content == null || content.isBlank()) {
                if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                    content = "[tool calls: " + msg.toolCalls().stream()
                        .map(tc -> tc.name())
                        .reduce((a, b) -> a + ", " + b).orElse("") + "]";
                } else {
                    continue;
                }
            }
            if (content.length() > 300) content = content.substring(0, 300) + "...";
            sb.append("[").append(role).append("] ").append(content).append("\n");
            count++;
            if (count > 30) {
                sb.append("... (truncated)\n");
                break;
            }
        }
        return sb.toString();
    }
}
