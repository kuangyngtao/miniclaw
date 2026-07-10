package com.clawkit.context;

/**
 * 上下文分区，对应 TODO.md 的 system/tools/history/memory/tool_result 分类。
 */
public enum ContextSection {
    /** 系统提示（L1-L5 主 prompt） */
    SYSTEM,
    /** 工具定义（JSON Schema 序列化后的 token） */
    TOOLS,
    /** 对话历史（user/assistant 消息往返） */
    HISTORY,
    /** 记忆（working memory + memory index + related sessions + conversation summary） */
    MEMORY,
    /** 工具执行结果 */
    TOOL_RESULT,
    /** 运行时注入（循环检测、进度提醒、停滞警告） */
    RUNTIME,
    /** 工作区状态（todo.md / plan.md） */
    WORKSPACE,
    /** 未分类（兜底，应记录 WARN） */
    UNCATEGORIZED
}
