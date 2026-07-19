package com.clawkit.engine.impl;

import com.clawkit.engine.MemoryHooks;
import com.clawkit.engine.PermissionMode;
import com.clawkit.engine.SkillRuntime;
import com.clawkit.memory.MemoryEntry;
import com.clawkit.memory.MemoryType;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolCall;
import com.clawkit.tools.schema.ToolDefinition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Definitions and handlers for engine-owned tools. */
final class InternalToolSuite {
    static final String TASK = "task";
    static final String SESSION_CONTEXT = "session_context";
    static final String SKILL_LOAD = "skill_load";
    static final String SKILL_UNLOAD = "skill_unload";
    static final String MEMORY_SAVE = "memory_save";
    static final String REMEMBER = "remember";

    private final InternalToolRouter router;
    private final ConversationSession session;
    private final SkillRuntime skills;
    private final MemoryHooks memory;
    private final SubAgentRunner subAgents;
    private final Consumer<String> memoryIndexChanged;
    private final Map<String, String> workingMemory = new ConcurrentHashMap<>();

    InternalToolSuite(InternalToolRouter router, ConversationSession session,
                      SkillRuntime skills, MemoryHooks memory, SubAgentRunner subAgents,
                      Consumer<String> memoryIndexChanged) {
        this.router = router;
        this.session = session;
        this.skills = skills;
        this.memory = memory;
        this.subAgents = subAgents;
        this.memoryIndexChanged = memoryIndexChanged;
        register();
    }

    List<ToolDefinition> definitions(PermissionMode permission, boolean enableSubAgents) {
        List<ToolDefinition> result = new ArrayList<>();
        if (permission != PermissionMode.PLAN && enableSubAgents) result.add(taskDefinition());
        if (session.available()) result.add(sessionDefinition());
        if (!skills.catalog().isEmpty()) {
            result.add(skillLoadDefinition()); result.add(skillUnloadDefinition());
        }
        if (permission != PermissionMode.PLAN) {
            if (memory.available()) result.add(memorySaveDefinition());
            result.add(rememberDefinition());
        }
        return result;
    }

    List<Message> workingMemoryContext() {
        if (workingMemory.isEmpty()) return List.of();
        StringBuilder text = new StringBuilder("[Working Memory]\n");
        workingMemory.forEach((key, value) -> text.append("- ").append(key).append(": ")
            .append(value).append('\n'));
        return List.of(Message.system(text.toString().stripTrailing()));
    }
    void clear() { workingMemory.clear(); }

    private void register() {
        router.register(TASK, (req, ctx) -> success(req.toolCallId(), TASK,
            subAgents.run(new ToolCall(req.toolCallId(), req.toolName(), req.arguments()),
                ctx, memory.memoryIndex()), writable(TASK, ToolRiskLevel.MEDIUM, false)),
            writable(TASK, ToolRiskLevel.MEDIUM, false),
            req -> taskDescriptor(req));
        router.register(SESSION_CONTEXT, (req, ctx) -> success(req.toolCallId(), SESSION_CONTEXT,
            session.searchContext(req.arguments().path("query").asText("")), readOnly(SESSION_CONTEXT)),
            readOnly(SESSION_CONTEXT), null);
        router.register(SKILL_LOAD, (req, ctx) -> success(req.toolCallId(), SKILL_LOAD,
            loadSkill(req.arguments().path("name").asText("")), readOnly(SKILL_LOAD)),
            readOnly(SKILL_LOAD), null);
        router.register(SKILL_UNLOAD, (req, ctx) -> success(req.toolCallId(), SKILL_UNLOAD,
            unloadSkill(req.arguments().path("name").asText("")), readOnly(SKILL_UNLOAD)),
            readOnly(SKILL_UNLOAD), null);
        router.register(MEMORY_SAVE, (req, ctx) -> success(req.toolCallId(), MEMORY_SAVE,
            saveMemory(req.arguments()), writable(MEMORY_SAVE, ToolRiskLevel.MEDIUM, true)),
            writable(MEMORY_SAVE, ToolRiskLevel.MEDIUM, true),
            req -> internalDescriptor("internal.memory_save",
                "memory:" + req.arguments().path("name").asText("unnamed"), req));
        router.register(REMEMBER, (req, ctx) -> success(req.toolCallId(), REMEMBER,
            remember(req.arguments().path("key").asText(""),
                req.arguments().path("value").asText("")),
            writable(REMEMBER, ToolRiskLevel.LOW, false)),
            writable(REMEMBER, ToolRiskLevel.LOW, false),
            req -> internalDescriptor("internal.remember",
                "working-memory:" + req.arguments().path("key").asText("unnamed"), req));
    }

    /** task 描述符：目标按指令内容区分，互不相同的并行子任务不互斥。 */
    private static com.clawkit.tools.action.ActionDescriptor taskDescriptor(
            com.clawkit.tools.ToolExecutionRequest req) {
        String instruction = req.arguments() != null
            ? req.arguments().path("instruction").asText("") : "";
        String key = com.clawkit.tools.action.Digests.sha256Hex(instruction).substring(0, 16);
        return internalDescriptor("internal.task", "task:" + key, req);
    }

    /** 引擎内部状态变更的保守描述符：进程内可逆，确定性验证无外部断言。 */
    private static com.clawkit.tools.action.ActionDescriptor internalDescriptor(
            String actionCode, String targetName, com.clawkit.tools.ToolExecutionRequest req) {
        String args = req.arguments() != null ? req.arguments().toString() : "{}";
        return new com.clawkit.tools.action.ActionDescriptor(
            actionCode,
            com.clawkit.tools.action.ActionTargets.internalTarget(targetName),
            com.clawkit.tools.action.Digests.sha256Hex(args),
            ToolRiskLevel.MEDIUM,
            com.clawkit.tools.action.Reversibility.REVERSIBLE,
            com.clawkit.tools.action.ActionReliability.idempotentSetter(),
            com.clawkit.tools.action.VerificationMode.MANUAL_REQUIRED,
            java.util.List.of(), java.util.List.of(),
            "re-run with corrected arguments", "engine-internal state");
    }

    private String loadSkill(String name) {
        if (name.isBlank()) return "Skill name is required.";
        var result = skills.load(name);
        return result.loaded() ? "Skill '" + name + "' loaded."
            : "[S-001] Skill '" + name + "' " + result.error() + ".";
    }
    private String unloadSkill(String name) {
        if (name.isBlank()) return "Skill name is required.";
        return skills.unload(name).unloaded() ? "Skill '" + name + "' unloaded."
            : "Skill '" + name + "' is not currently loaded.";
    }
    private String saveMemory(com.fasterxml.jackson.databind.JsonNode args) {
        String name = args.path("name").asText("");
        String content = args.path("content").asText("");
        if (name.isBlank() || content.isBlank()) return "name and content are required for memory_save.";
        MemoryType type;
        try { type = MemoryType.valueOf(args.path("type").asText("reference").toUpperCase()); }
        catch (IllegalArgumentException e) { type = MemoryType.REFERENCE; }
        MemoryEntry entry = new MemoryEntry(name, args.path("description").asText(""),
            type, Instant.now(), content);
        memory.remember(entry);
        memoryIndexChanged.accept(memory.memoryIndex());
        return "Memory saved: " + entry.filename() + " [" + type.name().toLowerCase() + "]";
    }
    private String remember(String key, String value) {
        if (key.isBlank() || value.isBlank()) return "key and value are required for remember.";
        workingMemory.put(key, value);
        return "Stored: " + key + " (" + value.length() + " chars). "
            + workingMemory.size() + " keys in working memory.";
    }

    private static ToolExecutionResult success(
            String id, String name, String output, ToolMetadata metadata) {
        return ToolExecutionResult.success(id, name, output, 0, metadata);
    }
    private static ToolMetadata readOnly(String name) {
        return ToolMetadata.of(name, "", null, true, ToolRiskLevel.LOW,
            false, false, java.util.Set.of());
    }
    private static ToolMetadata writable(
            String name, ToolRiskLevel risk, boolean approvalRequired) {
        return ToolMetadata.of(name, "", null, false, risk,
            approvalRequired, approvalRequired, java.util.Set.of());
    }

    // Definitions remain explicit so permission filtering never relies on tool names.
    private ToolDefinition taskDefinition() { return AgentInternalToolDefinitions.task(); }
    private ToolDefinition sessionDefinition() { return AgentInternalToolDefinitions.sessionContext(); }
    private ToolDefinition skillLoadDefinition() { return AgentInternalToolDefinitions.skillLoad(skills.catalog()); }
    private ToolDefinition skillUnloadDefinition() { return AgentInternalToolDefinitions.skillUnload(); }
    private ToolDefinition memorySaveDefinition() { return AgentInternalToolDefinitions.memorySave(); }
    private ToolDefinition rememberDefinition() { return AgentInternalToolDefinitions.remember(); }
}
