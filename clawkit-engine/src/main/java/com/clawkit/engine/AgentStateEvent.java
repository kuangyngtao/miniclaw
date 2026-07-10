package com.clawkit.engine;

import java.util.Map;

public record AgentStateEvent(
    AgentState state,
    int turnCount,
    Map<String, Object> metadata
) {
    public AgentStateEvent(AgentState state, int turnCount) {
        this(state, turnCount, Map.of());
    }
}
