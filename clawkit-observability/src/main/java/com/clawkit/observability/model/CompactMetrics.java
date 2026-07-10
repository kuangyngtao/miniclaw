package com.clawkit.observability.model;

/**
 * 上下文压缩指标。引用已有的 ContextBudgetReport 分区数据。
 */
public record CompactMetrics(
    String runId,
    int turnNumber,
    /** 压缩前消息数 */
    int beforeMessages,
    /** 压缩后消息数 */
    int afterMessages,
    /** 压缩前总 token */
    int beforeTokens,
    /** 压缩后总 token */
    int afterTokens,
    /** 压缩前预算状态 (OK/WARN/COMPACT_REQUIRED/HARD_LIMIT) */
    String beforeStatus,
    /** 压缩后预算状态 */
    String afterStatus,
    /** 压缩前各分区 token */
    java.util.Map<String, Integer> sectionsBefore,
    /** 压缩后各分区 token */
    java.util.Map<String, Integer> sectionsAfter,
    /** 被驱逐的 turn 组数 */
    int evictedGroups,
    /** 应用的压缩规则 */
    java.util.List<String> appliedRules
) {}
