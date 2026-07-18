package com.clawkit.engine;

import java.util.List;
import java.util.Optional;

/**
 * Session 持久化存储契约。
 *
 * <p>存储实现负责序列化/反序列化，不负责 Agent 状态。
 */
public interface SessionStore {

    /** 当前持久化 schema 版本 */
    int SCHEMA_VERSION = 1;

    Optional<SessionDocument> load(String sessionId);

    void save(SessionDocument document);

    List<SessionMeta> list();

    void delete(String sessionId);

    long fileSize(String sessionId);

    default boolean exists(String sessionId) {
        return load(sessionId).isPresent();
    }
}
