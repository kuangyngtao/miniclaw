package com.clawkit.engine;

import com.clawkit.tools.schema.Message;
import java.util.List;

/**
 * 会话历史：一次 run 内 mutable 的消息容器。
 *
 * <p>只保存真实对话事实；ephemeral 消息由 ContextPipeline 注入，不在此持久化。
 */
public interface SessionHistory {
    List<Message> messages();
    void append(Message message);
    void clear();
    SessionSnapshot snapshot();
    void restore(SessionSnapshot snapshot);

    /** Replace the complete factual conversation history atomically. */
    default void replace(List<Message> messages) {
        restore(SessionSnapshot.of(messages));
    }

    default boolean isEmpty() {
        return messages().isEmpty();
    }

    default int size() {
        return messages().size();
    }
}
