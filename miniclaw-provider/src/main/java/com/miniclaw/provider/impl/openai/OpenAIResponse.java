package com.miniclaw.provider.impl.openai;

import java.util.List;

record OpenAIResponse(List<OpenAIChoice> choices, OpenAIError error, String id, String model) {}
