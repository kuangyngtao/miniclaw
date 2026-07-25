package com.clawkit.context.impl;

import com.clawkit.context.*;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ContextPipeline 默认实现，编排已有压缩组件。
 *
 * <p>不替代 LadderedCompactor / MessageMasker / ContextBudgetAnalyzer ——
 * 它是编排层，按固定阶段组合这些组件。
 *
 * <p>build() 阶段：
 * <ol>
 *   <li>收集所有 fragment（按 ContextRequest 数据源）</li>
 *   <li>规范化（tokenize）</li>
 *   <li>按 priority 排序</li>
 *   <li>压平为消息列表</li>
 *   <li>预算分析 → ContextBudgetReport</li>
 *   <li>返回 ModelContext</li>
 * </ol>
 */
public class DefaultContextPipeline implements ContextPipeline {

    private final ContextManager compactor;
    private final ContextBudgetAnalyzer analyzer;
    private final Tokenizer tokenizer;
    private final ContextBudgetPolicy budgetPolicy;
    private final AdaptiveCompactionPolicy adaptivePolicy;
    private final AnchorSnapshotPlanner anchorPlanner;

    public DefaultContextPipeline(
        ContextManager compactor,
        ContextBudgetAnalyzer analyzer,
        Tokenizer tokenizer,
        ContextBudgetPolicy budgetPolicy
    ) {
        this(compactor, analyzer, tokenizer, budgetPolicy,
            AdaptiveCompactionPolicy.defaults(budgetPolicy));
    }

    public DefaultContextPipeline(
        ContextManager compactor,
        ContextBudgetAnalyzer analyzer,
        Tokenizer tokenizer,
        ContextBudgetPolicy budgetPolicy,
        AdaptiveCompactionPolicy adaptivePolicy
    ) {
        this.compactor = compactor;
        this.analyzer = analyzer;
        this.tokenizer = tokenizer;
        this.budgetPolicy = budgetPolicy;
        this.adaptivePolicy = adaptivePolicy;
        this.anchorPlanner = new AnchorSnapshotPlanner(tokenizer, budgetPolicy, adaptivePolicy);
    }

    @Override
    public ModelContext build(ContextRequest request) {
        List<ContextFragment> fragments = collectFragments(request);
        fragments = normalize(fragments);
        fragments.sort(Comparator.comparingInt(ContextFragment::priority));
        List<Message> messages = flatten(fragments);

        // budget analysis（无额外上下文时的初始分析）
        int toolTokens = estimateToolTokens(request.tools());
        var report = analyzer.analyze(messages, toolTokens, java.util.Map.of());

        return new ModelContext(List.copyOf(fragments), messages, report);
    }

    @Override
    public CompactionResult compact(CompactionRequest request) {
        long started = System.nanoTime();
        int originalMessages = request.modelContext().size();
        int toolDefTokens = request.toolDefTokens();
        List<Message> raw = removeDerivedAnchorMessages(request.modelContext());
        List<String> legacyConstraints = new ConstraintExtractor().extract(raw).stream()
            .map(Constraint::text).toList();

        // PA-3: snapshot from unmasked input, then inject exactly one canonical sidecar.
        AnchorSnapshotPlanner.Plan anchorPlan = anchorPlanner.prepare(raw, request.hint());
        List<Message> messages = new ArrayList<>(raw);
        var beforeReport = analyzer.analyze(messages, toolDefTokens, java.util.Map.of());
        var decisionReport = anchorPlan.snapshot().renderedText().isBlank()
            ? beforeReport
            : analyzer.analyze(messages, toolDefTokens,
                java.util.Map.of("anchor-reserve", anchorPlan.snapshot().renderedText()));
        CompactionLevel selected = adaptivePolicy.initialLevel(decisionReport, budgetPolicy, request);
        if (selected == CompactionLevel.L0_NONE) {
            return result(messages, beforeReport, beforeReport, legacyConstraints, List.of(),
                originalMessages, false, request, anchorPlan, List.of(), selected,
                null, started);
        }

        List<String> appliedRules = new ArrayList<>();
        messages = deterministicCleanup(messages);
        appliedRules.add("l1-deterministic");
        ContextBudgetReport currentReport = analyzer.analyze(
            messages, toolDefTokens, java.util.Map.of());
        if (selected == CompactionLevel.L1_DETERMINISTIC
            || adaptivePolicy.withinTarget(currentReport, budgetPolicy, request)) {
            return result(messages, beforeReport, currentReport, legacyConstraints, appliedRules,
                originalMessages, true, request, anchorPlan, List.of(),
                CompactionLevel.L1_DETERMINISTIC, null, started);
        }

        if (anchorPlan.failed()) {
            return result(messages, beforeReport, currentReport, legacyConstraints, appliedRules,
                originalMessages, true, request, anchorPlan, List.of(),
                CompactionLevel.L4_FAILED, anchorPlan.failureCode(), started);
        }

        messages = insertCanonicalSnapshot(messages, anchorPlan.snapshot());

        List<TurnGroup> evictedGroups = List.of();
        if (MessageMasker.shouldMask(request.turnCount())) {
            MessageMasker.MaskedContext masked = MessageMasker.mask(messages, request.turnCount());
            messages = masked.messages();
            evictedGroups = masked.evictedTurnGroups();
        }
        CompactionResult extractive = compactor.compact(messages,
            adaptivePolicy.targetTokens(budgetPolicy, request),
            new CompactionOptions(request.hint().profile(), evictedGroups,
                CompactionLevel.L2_EXTRACTIVE));
        messages = insertCanonicalSnapshot(extractive.messages(), anchorPlan.snapshot());
        appliedRules.addAll(extractive.appliedRules());
        currentReport = analyzer.analyze(messages, toolDefTokens, java.util.Map.of());
        selected = CompactionLevel.L2_EXTRACTIVE;

        int estimatedSummaryInputTokens = tokenizer.countTokens(messages)
            + evictedGroups.stream().mapToInt(group -> tokenizer.countTokens(group.messages())).sum();
        if (adaptivePolicy.shouldUseGenerative(currentReport, budgetPolicy, request,
            estimatedSummaryInputTokens)) {
            CompactionResult generative = compactor.compact(messages,
                adaptivePolicy.targetTokens(budgetPolicy, request),
                new CompactionOptions(request.hint().profile(), evictedGroups,
                    CompactionLevel.L3_GENERATIVE));
            if (generative.appliedRules().stream().anyMatch(rule -> rule.startsWith("l3"))) {
                messages = insertCanonicalSnapshot(generative.messages(), anchorPlan.snapshot());
                appliedRules.addAll(generative.appliedRules().stream()
                    .filter(rule -> !appliedRules.contains(rule)).toList());
                currentReport = analyzer.analyze(messages, toolDefTokens, java.util.Map.of());
                selected = CompactionLevel.L3_GENERATIVE;
            }
        }

        String failureCode = verifyFailure(messages, anchorPlan.snapshot(), currentReport, request);
        CompactionLevel finalLevel = failureCode == null ? selected : CompactionLevel.L4_FAILED;
        return result(messages, beforeReport, currentReport, legacyConstraints, appliedRules,
            originalMessages, true, request, anchorPlan, evictedGroups,
            finalLevel, failureCode, started);
    }

    private CompactionResult result(
        List<Message> messages,
        ContextBudgetReport beforeReport,
        ContextBudgetReport afterReport,
        List<String> retainedConstraints,
        List<String> appliedRules,
        int beforeMessages,
        boolean compacted,
        CompactionRequest request,
        AnchorSnapshotPlanner.Plan anchorPlan,
        List<TurnGroup> evictedGroups,
        CompactionLevel level,
        String failureCode,
        long started
    ) {
        List<String> lost = failureCode != null && !anchorPlan.snapshot().requiredIds().isEmpty()
            && !canonicalSnapshotPresent(messages, anchorPlan.snapshot())
            ? anchorPlan.snapshot().requiredIds() : List.of();
        long durationMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        CompactionAudit audit = new CompactionAudit(
            request.hint().profile().name(), anchorPlan.retainedIds(), lost,
            discardedRanges(evictedGroups), evictedGroups.size(), durationMs, failureCode,
            level, decisionReason(level, failureCode));
        return new CompactionResult(List.copyOf(messages), beforeReport, afterReport,
            retainedConstraints, List.copyOf(appliedRules), beforeMessages, messages.size(),
            compacted, audit);
    }

    private List<Message> deterministicCleanup(List<Message> messages) {
        List<Message> normalized = compactor.applyAlwaysOnRules(messages);
        List<Message> result = new ArrayList<>(normalized.size());
        Set<String> seenRebuildable = new LinkedHashSet<>();
        for (Message message : normalized) {
            String content = message.content();
            boolean rebuildable = content != null && message.role() == com.clawkit.tools.schema.Role.SYSTEM
                && (content.startsWith("[Runtime]")
                    || content.startsWith("[Workspace State]")
                    || content.startsWith("[Working Memory]")
                    || content.startsWith("[Related Past Sessions]"));
            if (!rebuildable || seenRebuildable.add(content)) result.add(message);
        }
        return result;
    }

    private List<Message> removeDerivedAnchorMessages(List<Message> messages) {
        return messages.stream().filter(message -> {
            String content = message.content();
            return content == null
                || (!content.startsWith("[Runtime][Compaction Anchors]")
                    && !content.startsWith("[Preserved Constraints]"));
        }).toList();
    }

    private List<Message> insertCanonicalSnapshot(List<Message> messages, AnchorSnapshot snapshot) {
        List<Message> result = new ArrayList<>(removeDerivedAnchorMessages(messages));
        if (snapshot == null || snapshot.renderedText().isBlank()) return result;
        int insertAt = 0;
        while (insertAt < result.size()
            && result.get(insertAt).role() == com.clawkit.tools.schema.Role.SYSTEM) {
            insertAt++;
        }
        result.add(insertAt, Message.system(snapshot.renderedText()));
        return result;
    }

    private boolean canonicalSnapshotPresent(List<Message> messages, AnchorSnapshot snapshot) {
        if (snapshot == null || snapshot.renderedText().isBlank()) return true;
        if (!snapshot.verify()) return false;
        long count = messages.stream()
            .filter(message -> snapshot.renderedText().equals(message.content()))
            .count();
        return count == 1 && snapshot.findMissingRequired().isEmpty();
    }

    private String verifyFailure(List<Message> messages, AnchorSnapshot snapshot,
                                 ContextBudgetReport report, CompactionRequest request) {
        if (!canonicalSnapshotPresent(messages, snapshot)) return "REQUIRED_ANCHOR_LOST";
        if (adaptivePolicy.exceedsModelHardLimit(report, budgetPolicy, request)) {
            return "COMPACT_HARD_LIMIT";
        }
        return null;
    }

    private List<DiscardedTurnRange> discardedRanges(List<TurnGroup> groups) {
        return groups.stream().map(group -> new DiscardedTurnRange(
            group.turnNumber(), group.turnNumber(), group.messages().size(),
            group.messages().stream().map(message -> message.role().name()).distinct().toList(),
            "TIER3_EVICTION")).toList();
    }

    private String decisionReason(CompactionLevel level, String failureCode) {
        if (failureCode != null) return failureCode;
        return switch (level) {
            case L0_NONE -> "within-warning-threshold";
            case L1_DETERMINISTIC -> "above-warning-threshold";
            case L2_EXTRACTIVE -> "above-compact-threshold";
            case L3_GENERATIVE -> "extractive-result-above-target";
            case L4_FAILED -> "compact-failed";
        };
    }

    // ── fragment collection ───────────────────────────────────────────

    private List<ContextFragment> collectFragments(ContextRequest req) {
        List<ContextFragment> fragments = new ArrayList<>();

        // SYSTEM: system prompt
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            var msg = Message.system(req.systemPrompt());
            fragments.add(new ContextFragment(
                "system", ContextSource.SYSTEM, ContextLifecycle.EPHEMERAL,
                ContextFragment.defaultPriority(ContextSource.SYSTEM),
                List.of(msg), tokenizer.countTokens(req.systemPrompt()),
                false, false));
        }

        // WORKSPACE: workspace context (todo.md / plan.md)
        if (req.workspaceContext() != null && !req.workspaceContext().isEmpty()) {
            int tokens = tokenizer.countTokens(req.workspaceContext());
            fragments.add(new ContextFragment(
                "workspace", ContextSource.WORKSPACE, ContextLifecycle.EPHEMERAL,
                ContextFragment.defaultPriority(ContextSource.WORKSPACE),
                req.workspaceContext(), tokens, false, false));
        }

        // SESSION: persistent history
        if (req.sessionHistory() != null && !req.sessionHistory().isEmpty()) {
            int tokens = tokenizer.countTokens(req.sessionHistory());
            fragments.add(new ContextFragment(
                "session", ContextSource.SESSION, ContextLifecycle.PERSISTED,
                ContextFragment.defaultPriority(ContextSource.SESSION),
                req.sessionHistory(), tokens, true, false));
        }

        // RUNTIME: loop detection, progress reminders
        if (req.runtimeContext() != null && !req.runtimeContext().isEmpty()) {
            int tokens = tokenizer.countTokens(req.runtimeContext());
            fragments.add(new ContextFragment(
                "runtime", ContextSource.RUNTIME, ContextLifecycle.EPHEMERAL,
                ContextFragment.defaultPriority(ContextSource.RUNTIME),
                req.runtimeContext(), tokens, false, false));
        }

        // MEMORY: working memory + related sessions
        if (req.memoryContext() != null && !req.memoryContext().isEmpty()) {
            int tokens = tokenizer.countTokens(req.memoryContext());
            fragments.add(new ContextFragment(
                "memory", ContextSource.MEMORY, ContextLifecycle.EPHEMERAL,
                ContextFragment.defaultPriority(ContextSource.MEMORY),
                req.memoryContext(), tokens, false, false));
        }

        // SKILL: active skill prompts
        if (req.skillContext() != null && !req.skillContext().isEmpty()) {
            int tokens = tokenizer.countTokens(req.skillContext());
            fragments.add(new ContextFragment(
                "skill", ContextSource.SKILL, ContextLifecycle.EPHEMERAL,
                ContextFragment.defaultPriority(ContextSource.SKILL),
                req.skillContext(), tokens, false, false));
        }

        // TOOLS: tool definitions
        if (req.tools() != null && !req.tools().isEmpty()) {
            int tokens = estimateToolTokens(req.tools());
            // tools are not messages — they're passed separately to LLM; we still track their budget
            fragments.add(new ContextFragment(
                "tools", ContextSource.TOOLS, ContextLifecycle.EPHEMERAL,
                ContextFragment.defaultPriority(ContextSource.TOOLS),
                List.of(), tokens, false, false));
        }

        return fragments;
    }

    // ── normalization ─────────────────────────────────────────────────

    private List<ContextFragment> normalize(List<ContextFragment> fragments) {
        // tokenize each fragment if not already done
        List<ContextFragment> result = new ArrayList<>();
        for (var f : fragments) {
            int tokens = f.tokenCount() > 0
                ? f.tokenCount()
                : tokenizer.countTokens(f.messages());
            result.add(new ContextFragment(
                f.id(), f.source(), f.lifecycle(), f.priority(),
                f.messages(), tokens, f.compactable(), f.sensitive()));
        }
        return result;
    }

    // ── flatten ───────────────────────────────────────────────────────

    private List<Message> flatten(List<ContextFragment> fragments) {
        List<Message> messages = new ArrayList<>();
        for (var f : fragments) {
            messages.addAll(f.messages());
        }
        return messages;
    }

    // ── helpers ───────────────────────────────────────────────────────

    private int estimateToolTokens(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) return 0;
        int total = 0;
        for (var tool : tools) {
            Object schema = tool.inputSchema();
            if (schema != null) total += tokenizer.countTokens(schema.toString());
            total += tokenizer.countTokens(tool.name() != null ? tool.name() : "");
            total += tokenizer.countTokens(tool.description() != null ? tool.description() : "");
        }
        return total;
    }
}
