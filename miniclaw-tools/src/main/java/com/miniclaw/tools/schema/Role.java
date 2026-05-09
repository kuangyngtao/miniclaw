package com.miniclaw.tools.schema;

public enum Role {
    SYSTEM,   // 系统提示词：确立 Agent 的性格与红线
    USER,     // 用户输入 / 工具执行返回结果 (Observation)
    ASSISTANT // 模型输出：包含推理或工具调用
}
