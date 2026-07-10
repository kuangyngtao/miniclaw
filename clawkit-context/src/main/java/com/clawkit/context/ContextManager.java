package com.clawkit.context;

import com.clawkit.context.impl.TurnGroup;
import com.clawkit.tools.schema.Message;
import java.util.List;

/**
 * 上下文管理器 — 阶梯压缩 + Token 监控。
 * 每轮 ReAct 开始前调用，确保上下文不超 token 预算。
 */
public interface ContextManager {

    /** 估算消息列表的 token 数 */
    int estimateTokens(List<Message> messages);

    /** 始终执行的清理规则（大 TOOL 掐头去尾），不依赖 token 预算 */
    default List<Message> applyAlwaysOnRules(List<Message> messages) {
        return messages;
    }

    /**
     * 预算驱动的压缩。engine 根据 ContextBudgetReport 决定触发时机。
     *
     * @param messages           待压缩消息
     * @param maxTokens          目标 token 上限（期望值，compact 尽力但不保证）
     * @param evictedTurnGroups  MessageMasker 驱逐的 T3 轮次组，供 Map-Reduce 摘要
     * @return CompactionResult  含压缩后消息 + 前后分区报告
     */
    default CompactionResult compact(List<Message> messages, int maxTokens,
                                     List<TurnGroup> evictedTurnGroups) {
        int beforeTokens = estimateTokens(messages);
        List<Message> result = compact(messages, maxTokens); // 委托旧方法
        int afterTokens = estimateTokens(result);
        return new CompactionResult(result,
            null, // beforeReport - 调用方自行补充
            null, // afterReport
            List.of(), List.of("legacy"));
    }

    /**
     * @deprecated 使用 compact(messages, maxTokens, evictedTurnGroups) 替代，
     *             返回 CompactionResult 含前后报告。
     */
    @Deprecated
    List<Message> compact(List<Message> messages, int maxTokens);
}
