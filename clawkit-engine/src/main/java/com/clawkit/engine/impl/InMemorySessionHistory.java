package com.clawkit.engine.impl;

import com.clawkit.engine.SessionHistory;
import com.clawkit.engine.SessionSnapshot;
import com.clawkit.tools.schema.Message;
import java.util.ArrayList;
import java.util.List;

/**
 * SessionHistory 的简单内存实现。
 */
public final class InMemorySessionHistory implements SessionHistory {
    private final List<Message> messages = new ArrayList<>();

    /** 内部使用的可变 List 视图（逐步迁移到接口方法） */
    @Override
    public List<Message> messages() {
        return List.copyOf(messages);
    }

    @Override
    public void append(Message message) {
        messages.add(message);
    }

    @Override
    public void clear() {
        messages.clear();
    }

    @Override
    public SessionSnapshot snapshot() {
        return SessionSnapshot.of(messages);
    }

    @Override
    public void restore(SessionSnapshot snapshot) {
        messages.clear();
        messages.addAll(snapshot.messages());
    }
}
