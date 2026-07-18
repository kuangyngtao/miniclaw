package com.clawkit.context;

/**
 * 上下文片段的来源。
 */
public enum ContextSource {
    /** 系统级 prompt（kernel、workspace rules、mode prompt） */
    SYSTEM,
    /** 工作区快照（todo.md、plan.md） */
    WORKSPACE,
    /** 持久会话消息 */
    SESSION,
    /** 运行时注入（死循环检测、进度提醒、硬上限等） */
    RUNTIME,
    /** 记忆召回（working memory、related sessions） */
    MEMORY,
    /** Skill 上下文 */
    SKILL,
    /** 工具定义列表 */
    TOOLS
}
