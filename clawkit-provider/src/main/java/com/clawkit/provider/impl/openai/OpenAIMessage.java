package com.clawkit.provider.impl.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
record OpenAIMessage(
    String role,
    String content,
    @JsonProperty("tool_calls") List<OpenAIToolCall> toolCalls,
    @JsonProperty("tool_call_id") String toolCallId,
    @JsonProperty("reasoning_content") String reasoningContent
) {
    OpenAIMessage(String role, String content, List<OpenAIToolCall> toolCalls,
                  String toolCallId) {
        this(role, content, toolCalls, toolCallId, null);
    }
}
