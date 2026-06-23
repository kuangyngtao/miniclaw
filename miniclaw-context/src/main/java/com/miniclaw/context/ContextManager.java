package com.miniclaw.context;

import com.miniclaw.context.impl.TurnGroup;
import com.miniclaw.tools.schema.Message;
import java.util.List;

/**
 * 上下文管理器 — 阶梯压缩 + Token 监控。
 * 每轮 ReAct 开始前调用，确保上下文不超 token 预算。
 */
public interface ContextManager {

    /** 估算消息列表的 token 数 */
    int estimateTokens(List<Message> messages);

    /** 阶梯压缩消息列表，使其不超过 maxTokens。最近 3 轮完整保留。 */
    List<Message> compact(List<Message> messages, int maxTokens);

    /** 阶梯压缩，接收软驱逐的轮次组供 Map-Reduce 压缩。默认委托给 2-arg 版本。 */
    default List<Message> compact(List<Message> messages, int maxTokens,
                                   List<TurnGroup> evictedTurnGroups) {
        return compact(messages, maxTokens);
    }
}
