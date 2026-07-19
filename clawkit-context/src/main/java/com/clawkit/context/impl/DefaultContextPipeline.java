package com.clawkit.context.impl;

import com.clawkit.context.*;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    public DefaultContextPipeline(
        ContextManager compactor,
        ContextBudgetAnalyzer analyzer,
        Tokenizer tokenizer,
        ContextBudgetPolicy budgetPolicy
    ) {
        this.compactor = compactor;
        this.analyzer = analyzer;
        this.tokenizer = tokenizer;
        this.budgetPolicy = budgetPolicy;
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
        List<Message> messages = new ArrayList<>(request.modelContext());
        int toolDefTokens = request.toolDefTokens();
        int turnCount = request.turnCount();

        // 1. always-on rules
        messages = compactor.applyAlwaysOnRules(messages);

        // 2. mask (turn-based)
        List<TurnGroup> evictedGroups = List.of();
        if (MessageMasker.shouldMask(turnCount)) {
            var masked = MessageMasker.mask(messages, turnCount);
            messages = masked.messages();
            evictedGroups = masked.evictedTurnGroups();
        }

        // 3. budget analyze
        var beforeReport = analyzer.analyze(messages, toolDefTokens, java.util.Map.of());

        int beforeMessages = messages.size();

        // 4. compact if needed
        var status = beforeReport.status();
        if (status == ContextBudgetReport.BudgetStatus.COMPACT_REQUIRED
            || status == ContextBudgetReport.BudgetStatus.HARD_LIMIT) {

            var result = compactor.compact(messages, budgetPolicy.targetTokens(), evictedGroups);
            CompactionResult cr = (CompactionResult) result;
            var afterReport = analyzer.analyze(cr.messages(), toolDefTokens, java.util.Map.of());

            return new CompactionResult(
                cr.messages(), beforeReport, afterReport,
                cr.retainedConstraints(), cr.appliedRules(),
                beforeMessages, cr.messages().size(), true,
                CompactionAudit.EMPTY);
        }

        // no compact needed
        return new CompactionResult(
            messages, beforeReport, beforeReport, List.of(), List.of(),
            beforeMessages, messages.size(), false,
            CompactionAudit.EMPTY);
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
