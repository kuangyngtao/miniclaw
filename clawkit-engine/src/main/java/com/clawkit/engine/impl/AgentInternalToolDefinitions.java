package com.clawkit.engine.impl;

import com.clawkit.context.SkillCatalog;
import com.clawkit.tools.schema.ToolDefinition;

/** JSON contracts for engine-owned tools. */
final class AgentInternalToolDefinitions {
    private AgentInternalToolDefinitions() {}

    static ToolDefinition task() { return new ToolDefinition(InternalToolSuite.TASK,
        "Delegate a self-contained subtask to an isolated explore or general sub-agent.", """
        {"type":"object","properties":{
          "instruction":{"type":"string","description":"Self-contained instruction"},
          "subagent_type":{"type":"string","enum":["explore","general"]}},
         "required":["instruction"]}
        """); }
    static ToolDefinition sessionContext() { return new ToolDefinition(InternalToolSuite.SESSION_CONTEXT,
        "Search past conversation sessions by keyword.", """
        {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
        """); }
    static ToolDefinition skillLoad(SkillCatalog catalog) {
        String values = catalog.entries().keySet().stream().map(n -> "\"" + n + "\"")
            .collect(java.util.stream.Collectors.joining(","));
        return new ToolDefinition(InternalToolSuite.SKILL_LOAD,
            "Load a skill's full prompt into the current session.",
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"enum\":["
                + values + "]}},\"required\":[\"name\"]}");
    }
    static ToolDefinition skillUnload() { return named(InternalToolSuite.SKILL_UNLOAD,
        "Unload a previously loaded skill.", "name"); }
    static ToolDefinition remember() { return new ToolDefinition(InternalToolSuite.REMEMBER,
        "Store a concise key-value pair in session working memory.", """
        {"type":"object","properties":{"key":{"type":"string"},"value":{"type":"string"}},
         "required":["key","value"]}
        """); }
    static ToolDefinition memorySave() { return new ToolDefinition(InternalToolSuite.MEMORY_SAVE,
        "Save durable user, feedback, project or reference memory.", """
        {"type":"object","properties":{
          "name":{"type":"string"},"description":{"type":"string"},
          "type":{"type":"string","enum":["user","feedback","project","reference"]},
          "content":{"type":"string"}},
         "required":["name","description","type","content"]}
        """); }
    private static ToolDefinition named(String name, String description, String field) {
        return new ToolDefinition(name, description,
            "{\"type\":\"object\",\"properties\":{\"" + field
                + "\":{\"type\":\"string\"}},\"required\":[\"" + field + "\"]}");
    }
}
