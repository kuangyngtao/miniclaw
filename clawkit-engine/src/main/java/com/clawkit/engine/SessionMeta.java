package com.clawkit.engine;

import java.time.Instant;

public record SessionMeta(
    String id,
    String name,
    Instant createdAt,
    Instant updatedAt,
    int messageCount,
    String firstUserMessage,
    String summary
) {}
