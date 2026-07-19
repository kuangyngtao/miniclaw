package com.clawkit.engine.impl;

import com.clawkit.context.CompactionHint;
import com.clawkit.context.CompactionRequest;
import com.clawkit.context.CompactionResult;
import com.clawkit.context.ContextBudgetAnalyzer;
import com.clawkit.context.ContextBudgetPolicy;
import com.clawkit.context.ContextBudgetReport;
import com.clawkit.context.ContextPipeline;
import com.clawkit.context.ContextRequest;
import com.clawkit.context.ModelContext;
import com.clawkit.context.Tokenizer;
import com.clawkit.context.Summarizer;
import com.clawkit.context.impl.DefaultContextPipeline;
import com.clawkit.context.impl.LadderedCompactor;
import com.clawkit.context.impl.TokenizerFactory;
import com.clawkit.tools.schema.Message;
import java.util.List;
import java.util.Map;

/** Owns context construction, compaction and diagnostic state for an engine. */
final class EngineContextCoordinator {
    record CompactionStatus(int beforeMessages, int afterMessages,
                            int beforeTokens, int afterTokens) {}

    private final int contextWindow;
    private final Tokenizer tokenizer;
    private final LadderedCompactor manager;
    private final ContextBudgetPolicy policy;
    private final ContextBudgetAnalyzer analyzer;
    private final ContextPipeline pipeline;
    private volatile ContextBudgetReport lastReport;
    private volatile CompactionStatus lastCompaction;

    EngineContextCoordinator(int contextWindow, String encoding, ContextPipeline supplied,
                             Summarizer summarizer) {
        this.contextWindow = contextWindow;
        this.tokenizer = TokenizerFactory.create(encoding);
        this.manager = new LadderedCompactor(summarizer, tokenizer);
        this.policy = ContextBudgetPolicy.of(contextWindow);
        this.analyzer = new ContextBudgetAnalyzer(tokenizer, policy);
        this.pipeline = supplied != null ? supplied
            : new DefaultContextPipeline(manager, analyzer, tokenizer, policy);
    }

    ModelContext build(ContextRequest request) {
        ModelContext context = pipeline.build(request);
        lastReport = context.budgetReport();
        return context;
    }

    CompactionResult compact(List<Message> messages, int toolTokens, int turn) {
        return compact(messages, toolTokens, turn, CompactionHint.GENERAL);
    }

    /** P1-A7：hint-aware compact */
    CompactionResult compact(List<Message> messages, int toolTokens, int turn,
                             CompactionHint hint) {
        CompactionResult result = pipeline.compact(
            new CompactionRequest(messages, toolTokens, turn, hint));
        lastReport = result.afterReport() != null ? result.afterReport() : result.beforeReport();
        if (result.compacted()) {
            lastCompaction = new CompactionStatus(result.beforeMessages(), result.afterMessages(),
                result.beforeReport().totalTokens(), result.afterReport().totalTokens());
        }
        return result;
    }

    List<Message> applyAlwaysOnRules(List<Message> messages) { return manager.applyAlwaysOnRules(messages); }
    int estimateTokens(List<Message> messages) { return manager.estimateTokens(messages); }
    ContextBudgetReport report(List<Message> messages, int toolTokens) {
        return lastReport != null ? lastReport : analyzer.analyze(messages, toolTokens, Map.of());
    }
    ContextBudgetReport lastReport() { return lastReport; }
    CompactionStatus lastCompaction() { return lastCompaction; }
    ContextBudgetPolicy policy() { return policy; }
    Tokenizer tokenizer() { return tokenizer; }
    int contextWindow() { return contextWindow; }
}
