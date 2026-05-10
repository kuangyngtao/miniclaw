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
}
