package com.miniclaw.context;

/**
 * 上下文管理器 — Prompt 动态组装 + Token 监控。
 * MVP: 简单截断策略（超出 N 轮丢弃最早消息）。
 * V2: 阶梯压缩 + 事件注入。
 */
public interface ContextManager {

    /** 将用户输入与当前上下文拼装为完整的 LLM 消息列表 */
    String buildPrompt(String userInput);

    /** 返回当前上下文的消息轮数 */
    int turnCount();

    /** 截断上下文，保留最近 N 轮 */
    void truncate(int keepTurns);

    /** 注入运行时事件提醒（如 "当前时间: xxx"） */
    void injectReminder(String reminder);
}
