package com.clawkit.context;

/**
 * Configurable decision policy for adaptive layered compaction.
 *
 * <p>The existing {@link ContextBudgetPolicy} remains the source of threshold ratios. This
 * policy only controls the minimum economic size of a generative step and anchor bounds.
 */
public record AdaptiveCompactionPolicy(
    int minimumGenerativeSavingsTokens,
    int maxAnchors,
    double anchorBudgetRatio,
    int maxAnchorTokens,
    int expectedRemainingProviderCalls,
    int estimatedSummaryOutputTokens
) {
    public static AdaptiveCompactionPolicy defaults(ContextBudgetPolicy budget) {
        int minimumSavings = Math.max(256, budget.contextWindow() / 200);
        return new AdaptiveCompactionPolicy(minimumSavings, 64, 0.10, 8192, 4, 1024);
    }

    public AdaptiveCompactionPolicy(int minimumGenerativeSavingsTokens, int maxAnchors,
                                    double anchorBudgetRatio, int maxAnchorTokens) {
        this(minimumGenerativeSavingsTokens, maxAnchors, anchorBudgetRatio,
            maxAnchorTokens, 4, 512);
    }

    public AdaptiveCompactionPolicy {
        if (minimumGenerativeSavingsTokens < 0) {
            throw new IllegalArgumentException("minimumGenerativeSavingsTokens must be >= 0");
        }
        if (maxAnchors <= 0) throw new IllegalArgumentException("maxAnchors must be > 0");
        if (!(anchorBudgetRatio > 0.0 && anchorBudgetRatio <= 1.0)) {
            throw new IllegalArgumentException("anchorBudgetRatio must be in (0, 1]");
        }
        if (maxAnchorTokens <= 0) throw new IllegalArgumentException("maxAnchorTokens must be > 0");
        if (expectedRemainingProviderCalls <= 0) {
            throw new IllegalArgumentException("expectedRemainingProviderCalls must be > 0");
        }
        if (estimatedSummaryOutputTokens < 0) {
            throw new IllegalArgumentException("estimatedSummaryOutputTokens must be >= 0");
        }
    }

    public CompactionLevel initialLevel(ContextBudgetReport report, ContextBudgetPolicy budget,
                                        CompactionRequest request) {
        long projected = projectedTokens(report, request);
        int capacity = effectiveCapacity(budget, request);
        if (projected <= ratioTokens(capacity, budget.warningRatio())) {
            return CompactionLevel.L0_NONE;
        }
        if (projected <= ratioTokens(capacity, budget.compactRatio())) {
            return CompactionLevel.L1_DETERMINISTIC;
        }
        return CompactionLevel.L2_EXTRACTIVE;
    }

    public boolean shouldUseGenerative(ContextBudgetReport extractiveReport,
                                       ContextBudgetPolicy budget,
                                       CompactionRequest request,
                                       int estimatedSummaryInputTokens) {
        int target = targetTokens(budget, request);
        long projectedSavings = projectedTokens(extractiveReport, request) - target;
        if (exceedsModelHardLimit(extractiveReport, budget, request)) return true;
        if (runBudgetLimitsCapacity(budget, request)
            && exceedsHardLimit(extractiveReport, budget, request)) return false;
        if (projectedSavings < minimumGenerativeSavingsTokens) return false;
        long futureSavings = projectedSavings * expectedRemainingProviderCalls;
        long summaryCost = (long) Math.max(0, estimatedSummaryInputTokens)
            + estimatedSummaryOutputTokens;
        return futureSavings > summaryCost;
    }

    public int anchorBudgetTokens(ContextBudgetPolicy budget) {
        int proportional = (int) Math.floor(budget.targetTokens() * anchorBudgetRatio);
        return Math.max(1, Math.min(maxAnchorTokens, proportional));
    }

    public int targetTokens(ContextBudgetPolicy budget, CompactionRequest request) {
        return ratioTokens(effectiveCapacity(budget, request), budget.targetRatio());
    }

    public boolean withinTarget(ContextBudgetReport report, ContextBudgetPolicy budget,
                                CompactionRequest request) {
        return projectedTokens(report, request) <= targetTokens(budget, request);
    }

    public boolean exceedsHardLimit(ContextBudgetReport report, ContextBudgetPolicy budget,
                                    CompactionRequest request) {
        return projectedTokens(report, request)
            >= ratioTokens(effectiveCapacity(budget, request), budget.hardLimitRatio());
    }

    public boolean exceedsModelHardLimit(ContextBudgetReport report, ContextBudgetPolicy budget,
                                         CompactionRequest request) {
        long projected = projectedTokens(report, request);
        return projected >= ratioTokens(budget.contextWindow(), budget.hardLimitRatio());
    }

    private long projectedTokens(ContextBudgetReport report, CompactionRequest request) {
        return (long) report.totalTokens() + request.reservedOutputTokens()
            + request.safetyMarginTokens();
    }

    private int effectiveCapacity(ContextBudgetPolicy budget, CompactionRequest request) {
        long remaining = request.runTokenBudgetRemaining();
        long capacity = remaining == Long.MAX_VALUE
            ? budget.contextWindow() : Math.min(budget.contextWindow(), remaining);
        return (int) Math.max(1L, capacity);
    }

    private boolean runBudgetLimitsCapacity(ContextBudgetPolicy budget, CompactionRequest request) {
        return request.runTokenBudgetRemaining() != Long.MAX_VALUE
            && request.runTokenBudgetRemaining() < budget.contextWindow();
    }

    private int ratioTokens(int capacity, double ratio) {
        return Math.max(1, (int) Math.floor(capacity * ratio));
    }
}
