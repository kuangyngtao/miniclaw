package com.miniclaw.provider.impl.openai;

record OpenAIToolCall(String id, String type, OpenAIFunction function) {}
