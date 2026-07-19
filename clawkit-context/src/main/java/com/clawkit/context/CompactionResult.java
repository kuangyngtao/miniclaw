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
    List<String> appliedRules,
    // ── PR-1: 扩展统计字段 ───────────────────────────────────────
    int beforeMessages,
    int afterMessages,
    boolean compacted,
    // ── P1-A6 ────────────────────────────────────────────────────────
    CompactionAudit audit
) {
    /** 兼容构造器（旧调用方） */
    public CompactionResult(
        List<Message> messages,
        ContextBudgetReport beforeReport,
        ContextBudgetReport afterReport,
        List<String> retainedConstraints,
        List<String> appliedRules
    ) {
        this(messages, beforeReport, afterReport, retainedConstraints, appliedRules,
            messages.size(), messages.size(), false, CompactionAudit.EMPTY);
    }
}
