package com.clawkit.provider.impl.openai;

import com.fasterxml.jackson.annotation.JsonProperty;

record OpenAICompletionTokenDetails(
    @JsonProperty("reasoning_tokens") Integer reasoningTokens
) {}
