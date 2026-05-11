package com.miniclaw.engine;

/**
 * Agent 主循环接口 — miniclaw 的控制中枢。
 * ReAct 范式: Think → Act → Observe → 循环，直到模型输出最终回复。
 */
public interface AgentLoop {

    /** 启动 Agent 循环，处理用户输入，返回模型最终文本回复 */
    String run(String userPrompt);

    /** 运行时切换思考模式 */
    void setThinkingMode(ThinkingMode mode);

    /** 运行时切换权限模式 */
    void setPermissionMode(PermissionMode mode);

    /** 查询当前权限模式 */
    PermissionMode permissionMode();

    /** 清空当前会话历史 */
    void clearSession();

    /** 中断当前 Agent 循环，run() 将在下一轮检查点返回 */
    void interrupt();

    /** 设置流式输出回调，下次 run() 生效 */
    void setOnToken(java.util.function.Consumer<String> onToken);

    /** 添加流式输出监听器（多路复用，不清除已有监听器） */
    void addOnTokenListener(java.util.function.Consumer<String> listener);

    /** 移除流式输出监听器 */
    void removeOnTokenListener(java.util.function.Consumer<String> listener);

    /** 尝试获取引擎执行权（防并发），成功返回 true */
    boolean tryAcquire();

    /** 释放引擎执行权 */
    void release();
}
