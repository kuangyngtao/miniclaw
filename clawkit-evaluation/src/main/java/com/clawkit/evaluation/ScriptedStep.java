package com.clawkit.evaluation;

import com.clawkit.tools.schema.Message;

import java.util.Set;

/**
 * ScriptedProvider 的单步剧本。
 * 每次 generate() 调用消费一个 step，严格校验 phase / turn / availableTools。
 */
public record ScriptedStep(
    String phase,
    Integer expectedTurn,
    boolean expectedStreaming,
    Set<String> expectedAvailableTools,
    Message response,
    RuntimeException error
) {
    public static ScriptedStep text(String phase, Integer turn, boolean streaming,
                                     Set<String> tools, String content) {
        return new ScriptedStep(phase, turn, streaming, tools,
            Message.assistant(content), null);
    }

    public static ScriptedStep toolCall(String phase, Integer turn, boolean streaming,
                                         Set<String> tools, Message toolCallMessage) {
        return new ScriptedStep(phase, turn, streaming, tools,
            toolCallMessage, null);
    }

    public static ScriptedStep error(String phase, Integer turn, boolean streaming,
                                      Set<String> tools, RuntimeException e) {
        return new ScriptedStep(phase, turn, streaming, tools, null, e);
    }

    /** 简化工厂：不校验 phase，不校验 turn，非流式，不校验工具 */
    public static ScriptedStep text(String content) {
        return text(null, null, false, null, content);
    }

    /** 简化工厂：不校验 phase，单工具调用 */
    public static ScriptedStep toolCall(Message toolCallMessage) {
        return toolCall(null, null, false, null, toolCallMessage);
    }

    /** 简化工厂：不校验 phase，抛出异常 */
    public static ScriptedStep error(RuntimeException e) {
        return error(null, null, false, null, e);
    }
}
