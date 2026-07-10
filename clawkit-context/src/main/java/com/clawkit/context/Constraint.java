package com.clawkit.context;

/** 关键约束：compact 中必须保留的信息片段。 */
public sealed interface Constraint {
    /** 约束的可匹配文本 */
    String text();

    record FilePath(String path) implements Constraint {
        @Override public String text() { return path; }
    }
    record ErrorCode(String code) implements Constraint {
        @Override public String text() { return code; }
    }
    record UnfinishedTodo(String todo) implements Constraint {
        @Override public String text() { return todo; }
    }
}
