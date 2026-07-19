package com.clawkit.context;

import java.util.List;

/**
 * P1-A6：被 discard 的 turn range 摘要。
 * 不从自由文本猜测 topic 或时间。
 */
public record DiscardedTurnRange(
    int fromTurn,
    int toTurn,
    int messageCount,
    List<String> roles,
    String reason
) {
    public DiscardedTurnRange {
        if (roles == null) roles = List.of();
        roles = List.copyOf(roles);
    }
}
