package com.clawkit.context;

/**
 * 上下文预算策略。由 engine 从 provider 的 contextWindow 构造，驱动所有预算决策。
 *
 * @param contextWindow   模型最大上下文窗口（token）
 * @param warningRatio    预警线，默认 0.70
 * @param compactRatio    强制 compact 线，默认 0.85
 * @param hardLimitRatio  硬保护线，默认 0.95
 * @param targetRatio     compact 目标线，默认 0.70
 */
public record ContextBudgetPolicy(
    int contextWindow,
    double warningRatio,
    double compactRatio,
    double hardLimitRatio,
    double targetRatio
) {
    public static final double DEFAULT_WARNING_RATIO = 0.70;
    public static final double DEFAULT_COMPACT_RATIO = 0.85;
    public static final double DEFAULT_HARD_LIMIT_RATIO = 0.95;
    public static final double DEFAULT_TARGET_RATIO = 0.70;

    /** 工厂方法：从 contextWindow 创建默认策略 */
    public static ContextBudgetPolicy of(int contextWindow) {
        return new ContextBudgetPolicy(contextWindow,
            DEFAULT_WARNING_RATIO, DEFAULT_COMPACT_RATIO,
            DEFAULT_HARD_LIMIT_RATIO, DEFAULT_TARGET_RATIO);
    }

    public int warningTokens()   { return (int) (contextWindow * warningRatio); }
    public int compactTokens()   { return (int) (contextWindow * compactRatio); }
    public int hardLimitTokens() { return (int) (contextWindow * hardLimitRatio); }
    public int targetTokens()    { return (int) (contextWindow * targetRatio); }
}
