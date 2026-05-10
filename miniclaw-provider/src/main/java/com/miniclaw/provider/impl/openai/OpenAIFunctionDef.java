package com.miniclaw.provider.impl.openai;

import com.fasterxml.jackson.databind.JsonNode;

record OpenAIFunctionDef(String name, String description, JsonNode parameters) {}
