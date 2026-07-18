package com.clawkit.engine.impl;

import com.clawkit.engine.MemoryHooks;
import com.clawkit.engine.ProviderGateway;
import com.clawkit.engine.RunPhase;
import com.clawkit.engine.RunScope;
import com.clawkit.memory.MemoryEntry;
import com.clawkit.memory.MemoryType;
import com.clawkit.memory.impl.DiskMemoryService;
import com.clawkit.memory.impl.KeywordScorer;
import com.clawkit.provider.ModelRequest;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Production memory lifecycle: recall before a run, extract after a run, and persist. */
public final class DefaultMemoryHooks implements MemoryHooks {
    private static final Logger log = LoggerFactory.getLogger(DefaultMemoryHooks.class);
    private static final int EXTRACTION_COOLDOWN = 5;
    private static final int MIN_NEW_MESSAGES = 10;
    private static final String EXTRACTION_PROMPT = """
        你是记忆提取器。从对话中识别值得跨会话保留的信息，让未来的对话能从中受益。
        只输出 JSON 数组；没有内容时返回 []。字段为 name、description、type、content。
        name 使用 kebab-case；type 只能是 user、feedback、project、reference；content 不超过 150 字。
        只提取用户偏好、长期反馈、项目决策和外部资源；跳过临时任务、工具输出和可从代码推导的信息。
        """;

    private final DiskMemoryService store;
    private final ProviderGateway gateway;
    private final com.clawkit.context.Tokenizer tokenizer;
    private final int contextWindow;
    private final ObjectMapper mapper = new ObjectMapper();
    private int turnsSinceLastExtraction;
    private int messagesAtLastExtraction;

    public DefaultMemoryHooks(DiskMemoryService store, ProviderGateway gateway,
                              int contextWindow, String encoding) {
        this.store = java.util.Objects.requireNonNull(store, "memory store required");
        this.gateway = java.util.Objects.requireNonNull(gateway, "provider gateway required");
        if (contextWindow <= 0) throw new IllegalArgumentException("contextWindow must be > 0");
        this.contextWindow = contextWindow;
        this.tokenizer = com.clawkit.context.impl.TokenizerFactory.create(encoding);
    }

    @Override
    public List<Message> beforeRun(MemoryRecallRequest request) {
        if (request == null || request.maxEntries() == 0 || request.task() == null
            || request.task().isBlank()) return List.of();
        try {
            var index = store.listIndex().stream()
                .filter(e -> !request.excludeNames().contains(e.name()))
                .toList();
            if (index.isEmpty()) return List.of();
            List<String> corpus = index.stream()
                .map(e -> e.name() + " " + e.description())
                .toList();
            KeywordScorer scorer = new KeywordScorer(corpus);
            return index.stream()
                .map(e -> new ScoredMemory(e,
                    scorer.score(request.task(), e.name() + " " + e.description())))
                .filter(e -> e.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
                .limit(request.maxEntries())
                .map(e -> store.load(e.entry().filename()))
                .filter(java.util.Objects::nonNull)
                .map(e -> Message.system("[Relevant Memory: " + e.name() + "]\n" + e.content()))
                .toList();
        } catch (Exception e) {
            log.warn("[Memory] recall failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public synchronized MemorySaveResult afterRun(MemoryExtractionRequest request) {
        if (request == null || request.contextHistory().isEmpty()) return MemorySaveResult.EMPTY;
        turnsSinceLastExtraction += Math.max(1, request.turnCount());
        int currentSize = request.contextHistory().size();
        // A cleared or newly loaded session starts a fresh extraction window.
        if (currentSize < messagesAtLastExtraction) messagesAtLastExtraction = 0;
        if (!request.force()) {
            if (turnsSinceLastExtraction < EXTRACTION_COOLDOWN) return MemorySaveResult.EMPTY;
            if (currentSize - messagesAtLastExtraction < MIN_NEW_MESSAGES) return MemorySaveResult.EMPTY;
            if (tokenizer.countTokens(formatMessages(request.contextHistory())) < contextWindow * 0.6) {
                return MemorySaveResult.EMPTY;
            }
        }

        List<Message> candidates = selectCandidates(request.contextHistory(), request.force());
        if (candidates.isEmpty()) return MemorySaveResult.EMPTY;
        RunScope scope = request.scope() != null
            ? request.scope().withPhase(RunPhase.MEMORY_EXTRACT)
            : new RunScope("memory-extract-" + java.util.UUID.randomUUID(), null, 0,
                RunPhase.MEMORY_EXTRACT, null);
        try {
            var response = gateway.generate(ModelRequest.of(
                List.of(Message.system(EXTRACTION_PROMPT), Message.user(formatMessages(candidates))),
                List.of()), scope);
            List<MemoryEntry> entries = parse(response.content());
            int saved = 0, skipped = 0, conflicts = 0;
            for (MemoryEntry entry : entries) {
                if (saved >= request.maxEntries()) break;
                MemoryEntry existing = store.load(entry.filename());
                if (existing != null && existing.content().equals(entry.content())) {
                    skipped++;
                    continue;
                }
                if (existing != null) conflicts++;
                store.save(entry);
                saved++;
            }
            reset(currentSize);
            return new MemorySaveResult(saved, skipped, conflicts);
        } catch (Exception e) {
            log.warn("[Memory] extraction failed: {}", e.getMessage());
            return MemorySaveResult.EMPTY;
        }
    }

    @Override public MemorySaveResult remember(MemoryEntry entry) {
        MemoryEntry existing = store.load(entry.filename());
        if (existing != null && existing.content().equals(entry.content())) {
            return new MemorySaveResult(0, 1, 0);
        }
        store.save(entry);
        return new MemorySaveResult(1, 0, existing == null ? 0 : 1);
    }
    @Override public String memoryIndex() { return store.loadIndex(); }
    @Override public boolean available() { return true; }

    private List<Message> selectCandidates(List<Message> history, boolean force) {
        int start = force ? 0 : Math.min(messagesAtLastExtraction, history.size());
        List<Message> selected = new ArrayList<>();
        for (int i = start; i < history.size() && selected.size() < 30; i++) {
            Message msg = history.get(i);
            if (msg.role() == Role.SYSTEM) continue;
            String content = msg.content();
            if (msg.role() == Role.TOOL && content != null && content.length() > 200) {
                selected.add(new Message(Role.TOOL, content.substring(0, 200) + "...",
                    null, msg.toolCallId()));
            } else {
                selected.add(msg);
            }
        }
        return selected;
    }

    private List<MemoryEntry> parse(String content) {
        if (content == null || content.isBlank()) return List.of();
        String json = content.strip();
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) json = json.substring(start, end).strip();
        }
        List<MemoryEntry> result = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isArray()) return List.of();
            for (JsonNode item : root) {
                String name = item.path("name").asText("").strip();
                String description = item.path("description").asText("").strip();
                String body = item.path("content").asText("").strip();
                if (name.isBlank() || body.isBlank()) continue;
                MemoryType type;
                try { type = MemoryType.valueOf(item.path("type").asText("reference").toUpperCase()); }
                catch (IllegalArgumentException e) { type = MemoryType.REFERENCE; }
                result.add(new MemoryEntry(name, description, type, Instant.now(), body));
            }
        } catch (Exception e) {
            log.warn("[Memory] invalid extraction JSON: {}", e.getMessage());
        }
        return result;
    }

    private static String formatMessages(List<Message> messages) {
        StringBuilder out = new StringBuilder("请分析以下对话片段：\n\n");
        int count = 0;
        for (Message message : messages) {
            String content = message.content();
            if (content == null || content.isBlank()) continue;
            if (content.length() > 300) content = content.substring(0, 300) + "...";
            out.append('[').append(message.role().name().toLowerCase()).append("] ")
                .append(content).append('\n');
            if (++count >= 40) break;
        }
        return out.toString();
    }

    private void reset(int currentSize) {
        turnsSinceLastExtraction = 0;
        messagesAtLastExtraction = currentSize;
    }

    private record ScoredMemory(DiskMemoryService.IndexEntry entry, double score) {}
}
