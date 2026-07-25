package com.clawkit.provider.impl.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
record OpenAIRequest(
    String model,
    List<OpenAIMessage> messages,
    List<OpenAITool> tools,
    Boolean stream,
    DeepSeekThinking thinking,
    @com.fasterxml.jackson.annotation.JsonProperty("reasoning_effort") String reasoningEffort,
    Double temperature,
    @com.fasterxml.jackson.annotation.JsonProperty("max_tokens") Integer maxTokens,
    @com.fasterxml.jackson.annotation.JsonProperty("stream_options") OpenAIStreamOptions streamOptions
) {
    OpenAIRequest(String model, List<OpenAIMessage> messages, List<OpenAITool> tools,
                  Boolean stream) {
        this(model, messages, tools, stream, null, null, null, null, null);
    }
}
