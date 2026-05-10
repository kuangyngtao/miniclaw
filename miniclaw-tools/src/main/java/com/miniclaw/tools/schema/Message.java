package com.miniclaw.tools.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record Message(
    Role role,
    String content,
    List<ToolCall> toolCalls,
    String toolCallId
) {
    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null, null);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null, null);
    }

    public static Message assistantWithTools(List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, null, toolCalls, null);
    }

    public static Message toolResult(String toolCallId, String output) {
        return new Message(Role.TOOL, output, null, toolCallId);
    }
}
