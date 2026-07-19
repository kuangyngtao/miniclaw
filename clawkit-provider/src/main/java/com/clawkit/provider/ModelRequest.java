package com.clawkit.provider;

import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolDefinition;
import java.util.List;

/**
 * 统一模型请求，替代裸 List 参数。
 *
 * <p>P1-G：携带 {@link ExecutionControl}，取消、deadline 和预算贯穿到
 * Provider 传输层（timeout 收紧、退避前检查、阻塞请求可中断）。
 */
public record ModelRequest(
    List<Message> messages,
    List<ToolDefinition> tools,
    ModelParameters parameters,
    ExecutionControl control
) {
    public ModelRequest {
        if (messages == null) throw new IllegalArgumentException("messages required");
        if (control == null) control = ExecutionControl.none();
    }

    /** 兼容构造器（无 ExecutionControl） */
    public ModelRequest(List<Message> messages, List<ToolDefinition> tools,
                        ModelParameters parameters) {
        this(messages, tools, parameters, ExecutionControl.none());
    }

    public static ModelRequest of(List<Message> messages, List<ToolDefinition> tools) {
        return new ModelRequest(messages, tools, ModelParameters.DEFAULT);
    }

    public static ModelRequest of(List<Message> messages, List<ToolDefinition> tools,
                                  ExecutionControl control) {
        return new ModelRequest(messages, tools, ModelParameters.DEFAULT, control);
    }

    /** 替换 ExecutionControl。 */
    public ModelRequest withControl(ExecutionControl newControl) {
        return new ModelRequest(messages, tools, parameters, newControl);
    }
}
