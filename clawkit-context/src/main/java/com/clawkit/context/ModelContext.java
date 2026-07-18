package com.clawkit.context;

import com.clawkit.tools.schema.Message;
import java.util.List;

/**
 * 模型上下文：ContextPipeline 的构建结果。
 *
 * <p>包含已排序、已规范化的 fragment 列表和压平后的消息列表。
 */
public record ModelContext(
    List<ContextFragment> fragments,
    List<Message> messages,
    ContextBudgetReport budgetReport
) {
    public ModelContext {
        if (fragments == null) throw new IllegalArgumentException("fragments is required");
        if (messages == null) throw new IllegalArgumentException("messages is required");
        if (budgetReport == null) throw new IllegalArgumentException("budgetReport is required");
    }

    /** 总 token 数（来自 budgetReport） */
    public int totalTokens() {
        return budgetReport.totalTokens();
    }

    /** persistable fragment 数量 */
    public long persistableCount() {
        return fragments.stream().filter(f -> f.lifecycle() != ContextLifecycle.EPHEMERAL).count();
    }
}
