package com.clawkit.evaluation;

/**
 * 单个 benchmark case 的指标预算上限。
 * 超过任一预算 → MetricBudgetScorer 报 FAIL。
 */
public record MetricBudget(
    int maxTurns,
    int maxToolCalls,
    int maxToolFailures,
    int maxProviderCalls,
    int maxProviderRetries,
    int maxCompactions
) {
    public static MetricBudget liberal() {
        return new MetricBudget(20, 15, 5, 20, 3, 3);
    }

    public static MetricBudget tight() {
        return new MetricBudget(5, 5, 1, 5, 1, 1);
    }

    public static MetricBudget standard() {
        return new MetricBudget(10, 8, 3, 10, 2, 2);
    }
}
