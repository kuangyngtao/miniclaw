package com.miniclaw.tools.schema;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Role {
    SYSTEM,    // 系统提示词：确立 Agent 的性格与红线
    USER,      // 用户输入
    ASSISTANT, // 模型输出：包含推理或工具调用
    TOOL;      // 工具执行结果，OpenAI / DeepSeek API 要求 role: "tool"

    @JsonValue
    public String toLower() {
        return name().toLowerCase();
    }
}
