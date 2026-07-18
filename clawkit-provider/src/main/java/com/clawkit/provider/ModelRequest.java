package com.clawkit.provider;

import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolDefinition;
import java.util.List;

/** 统一模型请求，替代裸 List 参数。 */
public record ModelRequest(
    List<Message> messages,
    List<ToolDefinition> tools,
    ModelParameters parameters
) {
    public ModelRequest {
        if (messages == null) throw new IllegalArgumentException("messages required");
    }

    public static ModelRequest of(List<Message> messages, List<ToolDefinition> tools) {
        return new ModelRequest(messages, tools, ModelParameters.DEFAULT);
    }
}
