package com.clawkit.engine;

import com.clawkit.tools.schema.Message;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 持久化的 Session 文档。
 */
public record SessionDocument(
    int schemaVersion,
    String sessionId,
    String name,
    Instant createdAt,
    Instant updatedAt,
    List<Message> messages,
    Map<String, String> metadata
) {
    public SessionDocument {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId required");
        if (messages == null) throw new IllegalArgumentException("messages must not be null");
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be >= 1");
        messages = List.copyOf(messages);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public SessionDocument withMessages(List<Message> newMessages) {
        return new SessionDocument(schemaVersion, sessionId, name, createdAt, Instant.now(),
            newMessages, metadata);
    }
}
