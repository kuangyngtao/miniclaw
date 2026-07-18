package com.clawkit.context;

import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolDefinition;
import java.util.List;

/**
 * 上下文管线构建请求，包含所有 input 数据源。
 *
 * <p>ContextPipeline.build() 的输入参数。
 */
public record ContextRequest(
    String systemPrompt,
    List<Message> sessionHistory,
    List<Message> workspaceContext,
    List<Message> runtimeContext,
    List<Message> memoryContext,
    List<Message> skillContext,
    List<ToolDefinition> tools,
    ContextBudgetPolicy budget
) {
    public ContextRequest {
        if (budget == null) throw new IllegalArgumentException("budget is required");
    }

    /** 返回所有 context 消息中 persistable=true 的消息数（用于持久化过滤验证） */
    public int persistableMessageCount() {
        return (sessionHistory != null ? sessionHistory.size() : 0);
    }
}
