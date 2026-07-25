package com.clawkit.provider.impl.openai;

import com.fasterxml.jackson.annotation.JsonProperty;

record OpenAIStreamOptions(@JsonProperty("include_usage") boolean includeUsage) {}
