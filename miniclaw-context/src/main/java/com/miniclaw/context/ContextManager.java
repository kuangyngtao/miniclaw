package com.miniclaw.context;

import com.miniclaw.tools.schema.Message;
import java.util.List;

/**
 * 上下文管理器 — 阶梯压缩 + Token 监控。
 * 每轮 ReAct 开始前调用，确保上下文不超 token 预算。
 */
public interface ContextManager {

    /** 估算消息列表的 token 数（1 token ≈ 3 字符英文 / 4 字符中文） */
    int estimateTokens(List<Message> messages);

    /** 阶梯压缩消息列表，使其不超过 maxTokens。最近 3 轮完整保留。 */
    List<Message> compact(List<Message> messages, int maxTokens);
}
