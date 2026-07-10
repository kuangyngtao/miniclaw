package com.clawkit.context;

import com.clawkit.tools.schema.Message;
import java.util.List;

/**
 * 压缩结果，由 LadderedCompactor.compact() 返回。
 *
 * @param messages            压缩后的消息列表
 * @param beforeReport        压缩前预算报告
 * @param afterReport         压缩后预算报告
 * @param retainedConstraints 保留的关键约束（Phase 5）
 * @param appliedRules        应用的规则列表（"always-on", "pressure", "l3-map-reduce"）
 */
public record CompactionResult(
    List<Message> messages,
    ContextBudgetReport beforeReport,
    ContextBudgetReport afterReport,
    List<String> retainedConstraints,
    List<String> appliedRules
) {}
