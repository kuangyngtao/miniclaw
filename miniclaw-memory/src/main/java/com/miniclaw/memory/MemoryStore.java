package com.miniclaw.memory;

/**
 * 记忆存储接口 — 基于文件系统的持久化状态。
 * 核心哲学: 不维护内部变量，磁盘即真相。
 * MVP: 读写 TODO.md / AGENTS.md 等 Markdown 工作文件。
 */
public interface MemoryStore {

    /** 读出指定文件内容 */
    String read(String filePath);

    /** 写入（覆盖或追加） */
    void write(String filePath, String content);

    /** 向文件追加一行 */
    void append(String filePath, String line);

    /** 列出工作目录下所有 Markdown 工作文件 */
    java.util.List<String> listWorkFiles();
}
