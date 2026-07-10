package com.clawkit.context;

import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.Role;
import java.util.*;

/**
 * 上下文预算分析器。
 * 接收 messages、工具定义、skill/memory 片段，输出 ContextBudgetReport。
 *
 * <p>分类规则（规则化，不使用 LLM）：
 * <pre>
 * 前缀/角色                       → ContextSection
 * ─────────────────────────────────────────────
 * Role.SYSTEM + "[Runtime]"      → RUNTIME
 * Role.SYSTEM + "[Working Memory]" → MEMORY
 * Role.SYSTEM + "[Related Past Sessions]" → MEMORY
 * Role.SYSTEM + "[Conversation Summary]" → MEMORY
 * Role.SYSTEM + "[Workspace State]" → WORKSPACE
 * Role.SYSTEM + "[Preserved Constraints]" → RUNTIME
 * Role.SYSTEM (other)            → SYSTEM
 * Role.USER                      → HISTORY
 * Role.ASSISTANT                 → HISTORY
 * Role.TOOL                      → TOOL_RESULT
 * </pre>
 */
public class ContextBudgetAnalyzer {

    private static final LinkedHashMap<String, ContextSection> SYSTEM_PREFIX_RULES = new LinkedHashMap<>();
    static {
        SYSTEM_PREFIX_RULES.put("[Runtime]",              ContextSection.RUNTIME);
        SYSTEM_PREFIX_RULES.put("[Working Memory]",       ContextSection.MEMORY);
        SYSTEM_PREFIX_RULES.put("[Related Past Sessions]",ContextSection.MEMORY);
        SYSTEM_PREFIX_RULES.put("[Conversation Summary]", ContextSection.MEMORY);
        SYSTEM_PREFIX_RULES.put("[Workspace State]",      ContextSection.WORKSPACE);
        SYSTEM_PREFIX_RULES.put("[Preserved Constraints]",ContextSection.RUNTIME);
    }

    private final Tokenizer tokenizer;
    private final ContextBudgetPolicy policy;

    public ContextBudgetAnalyzer(Tokenizer tokenizer, ContextBudgetPolicy policy) {
        this.tokenizer = tokenizer;
        this.policy = policy;
    }

    /**
     * 分析消息列表的预算。
     * @param messages        所有消息
     * @param toolDefTokens   工具定义 token 数（单独传入，计入 TOOLS）
     * @param extraFragments  额外上下文片段（skill prompt、memory index 等），key=可读标签
     */
    public ContextBudgetReport analyze(
            List<Message> messages,
            int toolDefTokens,
            Map<String, String> extraFragments) {

        var sections = new EnumMap<ContextSection, Integer>(ContextSection.class);
        for (var section : ContextSection.values()) {
            sections.put(section, 0);
        }

        int messageCount = 0;
        for (var msg : messages) {
            messageCount++;
            var section = classify(msg);
            String content = msg.content();
            int tokens = content != null ? tokenizer.countTokens(content) : 0;
            sections.merge(section, tokens, Integer::sum);
        }

        // TOOLS 分区：工具定义
        sections.merge(ContextSection.TOOLS, toolDefTokens, Integer::sum);

        // 额外片段
        for (var entry : extraFragments.entrySet()) {
            int tokens = tokenizer.countTokens(entry.getValue());
            sections.merge(classifyExtra(entry.getKey()), tokens, Integer::sum);
        }

        int total = sections.values().stream().mapToInt(Integer::intValue).sum();
        double pct = (double) total / policy.contextWindow();
        var status = classifyStatus(pct);
        String action = buildSuggestedAction(status, pct);

        return new ContextBudgetReport(total, pct, status,
            Collections.unmodifiableMap(sections), messageCount, action);
    }

    /** 对单条消息分类 */
    public static ContextSection classify(Message msg) {
        if (msg.role() == Role.TOOL)       return ContextSection.TOOL_RESULT;
        if (msg.role() == Role.USER)       return ContextSection.HISTORY;
        if (msg.role() == Role.ASSISTANT)  return ContextSection.HISTORY;
        if (msg.role() == Role.SYSTEM) {
            String content = msg.content();
            if (content == null || content.isEmpty()) return ContextSection.SYSTEM;
            for (var entry : SYSTEM_PREFIX_RULES.entrySet()) {
                if (content.startsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return ContextSection.SYSTEM;
        }
        return ContextSection.UNCATEGORIZED;
    }

    /** 判断 SYSTEM 消息是否为运行时注入（不应持久化） */
    public static boolean isRuntimeSystemMessage(Message msg) {
        if (msg.role() != Role.SYSTEM) return false;
        String c = msg.content();
        if (c == null) return false;
        return c.startsWith("[Runtime]")
            || c.startsWith("[Working Memory]")
            || c.startsWith("[Related Past Sessions]")
            || c.startsWith("[Workspace State]")
            || c.startsWith("[Preserved Constraints]");
    }

    private ContextBudgetReport.BudgetStatus classifyStatus(double pct) {
        if (pct >= policy.hardLimitRatio()) return ContextBudgetReport.BudgetStatus.HARD_LIMIT;
        if (pct >= policy.compactRatio())   return ContextBudgetReport.BudgetStatus.COMPACT_REQUIRED;
        if (pct >= policy.warningRatio())   return ContextBudgetReport.BudgetStatus.WARN;
        return ContextBudgetReport.BudgetStatus.OK;
    }

    private String buildSuggestedAction(ContextBudgetReport.BudgetStatus status, double pct) {
        return switch (status) {
            case OK              -> "预算正常";
            case WARN            -> String.format("预算预警 (%.0f%%), 建议留意", pct * 100);
            case COMPACT_REQUIRED -> String.format("需 compact (%.0f%%), 目标 %.0f%%",
                                                    pct * 100, policy.targetRatio() * 100);
            case HARD_LIMIT      -> String.format("硬保护 (%.0f%%), compact 后仍超限, 请 /compact 或新会话", pct * 100);
        };
    }

    private ContextSection classifyExtra(String label) {
        if (label.contains("skill") || label.contains("tool")) return ContextSection.TOOLS;
        if (label.contains("memory") || label.contains("index")) return ContextSection.MEMORY;
        return ContextSection.SYSTEM;
    }
}
