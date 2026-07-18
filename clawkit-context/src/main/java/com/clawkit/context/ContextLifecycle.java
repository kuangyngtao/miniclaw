package com.clawkit.context;

/**
 * 上下文片段的生命周期。
 */
public enum ContextLifecycle {
    /** 仅当前 turn 有效，不持久化（runtime 提醒等） */
    EPHEMERAL,
    /** 可持久化但尚未写入（本 run 新增的可持久消息） */
    PERSISTABLE,
    /** 已持久化（从 session 文件加载的消息） */
    PERSISTED
}
