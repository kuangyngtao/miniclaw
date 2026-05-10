package com.miniclaw.provider.impl.openai;

import com.fasterxml.jackson.annotation.JsonProperty;

record OpenAIChoice(
    int index,
    OpenAIMessage message,
    @JsonProperty("finish_reason") String finishReason
) {}
