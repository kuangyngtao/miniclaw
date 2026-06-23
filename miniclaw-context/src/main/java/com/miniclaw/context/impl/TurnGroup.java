package com.miniclaw.context.impl;

import com.miniclaw.tools.schema.Message;
import java.util.List;
import java.util.Map;

/**
 * 语义完整的轮次组：一个 user 消息 + 其 assistant 响应 + 关联 tool 往返。
 * MessageMasker 驱逐 T3 消息时按组收集，供 Map-Reduce 压缩使用。
 */
public record TurnGroup(int turnNumber, List<Message> messages) {

    public static List<TurnGroup> fromEvictionMap(
            Map<Integer, List<Message>> evictionMap) {
        return evictionMap.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> new TurnGroup(e.getKey(), List.copyOf(e.getValue())))
            .toList();
    }
}
