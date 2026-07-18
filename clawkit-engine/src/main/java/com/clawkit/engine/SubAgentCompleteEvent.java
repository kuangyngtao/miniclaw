package com.clawkit.engine;

public record SubAgentCompleteEvent(String summary, int turnsUsed, int tokens, long durationMs) {}
