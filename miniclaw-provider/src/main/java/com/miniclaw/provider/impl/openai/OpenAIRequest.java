package com.miniclaw.provider.impl.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
record OpenAIRequest(
    String model,
    List<OpenAIMessage> messages,
    List<OpenAITool> tools,
    Boolean stream
) {}
