package com.clawkit.provider.impl.openai;

import com.fasterxml.jackson.annotation.JsonProperty;

record OpenAIUsage(
    @JsonProperty("prompt_tokens") Integer promptTokens,
    @JsonProperty("completion_tokens") Integer completionTokens,
    @JsonProperty("total_tokens") Integer totalTokens,
    @JsonProperty("prompt_cache_hit_tokens") Integer promptCacheHitTokens,
    @JsonProperty("prompt_cache_miss_tokens") Integer promptCacheMissTokens,
    @JsonProperty("completion_tokens_details") OpenAICompletionTokenDetails completionTokenDetails
) {}
