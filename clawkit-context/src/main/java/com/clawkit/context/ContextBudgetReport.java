package com.clawkit.context;

import java.util.Map;

/**
 * 上下文预算报告，由 ContextBudgetAnalyzer 生成。
 *
 * @param totalTokens      总 token 估算
 * @param percentage       占用百分比（0.0 ~ 1.0+）
 * @param status           预算状态
 * @param sections         各分区 token
 * @param messageCount     消息总数
 * @param suggestedAction  建议动作（人可读）
 */
public record ContextBudgetReport(
    int totalTokens,
    double percentage,
    BudgetStatus status,
    Map<ContextSection, Integer> sections,
    int messageCount,
    String suggestedAction
) {
    public enum BudgetStatus {
        /** 正常：低于预警线 */
        OK,
        /** 预警：超过 70% */
        WARN,
        /** 需 compact：超过 85% */
        COMPACT_REQUIRED,
        /** 硬保护：超过 95% */
        HARD_LIMIT
    }
}
