package com.miniclaw.engine;

import java.util.List;

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

    /** 注册状态变更监听器，监听引擎生命周期中的状态转换。默认空操作。 */
    default void onStateChange(java.util.function.Consumer<AgentStateEvent> listener) {}

    /** 尝试获取引擎执行权（防并发），成功返回 true */
    boolean tryAcquire();

    /** 释放引擎执行权 */
    void release();

    /** 以给定名称保存当前会话，返回会话 ID */
    default String saveSession(String name) { throw new UnsupportedOperationException(); }

    /** 从磁盘加载一个已保存的会话，替换当前会话历史 */
    default void loadSession(String sessionId) { throw new UnsupportedOperationException(); }

    /** 列出所有已保存的会话 */
    default List<SessionMeta> listSessions() { throw new UnsupportedOperationException(); }

    /** 删除一个已保存的会话 */
    default void deleteSession(String sessionId) { throw new UnsupportedOperationException(); }

    /** 启动新会话（清空当前上下文） */
    default String newSession() { throw new UnsupportedOperationException(); }
}
