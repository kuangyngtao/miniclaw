package com.miniclaw.tools.schema;

/**
 * 任务类型 — 决定子 Agent 被赋予的工具集。
 */
public enum TaskType {
    /** 仅读工具：read, glob, grep, web_fetch */
    EXPLORE,
    /** 读写工具：read, write, edit, bash, glob, grep, web_fetch, todo_write */
    MODIFY,
    /** 编译/测试：bash */
    VERIFY
}
