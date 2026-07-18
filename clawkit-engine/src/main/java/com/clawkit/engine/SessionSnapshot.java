package com.clawkit.engine;

import com.clawkit.tools.schema.Message;
import java.time.Instant;
import java.util.List;

/**
 * Session 快照：用于跨 run 持久化和恢复。
 */
public record SessionSnapshot(int schemaVersion, List<Message> messages, Instant createdAt) {
    public static final int CURRENT_VERSION = 1;

    public SessionSnapshot {
        if (messages == null) throw new IllegalArgumentException("messages must not be null");
    }

    public static SessionSnapshot of(List<Message> messages) {
        return new SessionSnapshot(CURRENT_VERSION, List.copyOf(messages), Instant.now());
    }
}
