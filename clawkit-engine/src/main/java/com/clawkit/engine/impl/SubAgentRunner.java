package com.clawkit.engine.impl;

import com.clawkit.engine.AgentRuntimeDependencies;
import com.clawkit.engine.ProviderGateway;
import com.clawkit.engine.SubAgentCompleteEvent;
import com.clawkit.engine.SubAgentSpawnEvent;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.tools.Registry;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.schema.ToolCall;
import com.clawkit.tools.schema.ToolDefinition;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Creates and runs isolated child agents. */
final class SubAgentRunner {
    private static final Logger log = LoggerFactory.getLogger(SubAgentRunner.class);
    private final Registry registry;
    private volatile ProviderGateway gateway;
    private final EngineContextCoordinator context;
    private final String workDir;
    private final EngineEventHub events;

    SubAgentRunner(Registry registry, ProviderGateway gateway, EngineContextCoordinator context,
                   String workDir, EngineEventHub events) {
        this.registry = registry;
        this.gateway = gateway;
        this.context = context;
        this.workDir = workDir;
        this.events = events;
    }

    void setGateway(ProviderGateway gateway) {
        this.gateway = gateway;
    }

    String run(ToolCall call, ToolExecutionContext execution, String memoryIndex) {
        String instruction = call.arguments().path("instruction").asText("");
        if (instruction.isBlank()) return "Error: sub-agent instruction is required.";
        String type = call.arguments().path("subagent_type").asText("general");
        if (!"explore".equals(type)) type = "general";
        int maxTurns = "explore".equals(type) ? 15 : 25;
        events.subAgentStarted(new SubAgentSpawnEvent(instruction, type, maxTurns));

        ToolRegistry childRegistry = new ToolRegistry();
        for (ToolDefinition definition : registry.getAvailableTools()) {
            if (InternalToolSuite.TASK.equals(definition.name())) continue;
            if ("explore".equals(type) && !registry.isReadOnly(definition.name())) continue;
            registry.lookup(definition.name()).ifPresent(childRegistry::register);
        }

        var deps = new AgentRuntimeDependencies(gateway, null, childRegistry,
            context.contextWindow(), context.tokenizer().encodingName(),
            events.recorder(), null, null);
        AgentEngine child = new AgentEngine(deps, workDir, ThinkingMode.OFF, memoryIndex);
        child.configureAsChild(execution.runId());
        // P1-G1：父取消级联到子 run，父子共享同一预算账本
        child.attachParentControl(execution.control());
        child.setPermissionMode(com.clawkit.engine.PermissionMode.valueOf(
            execution.permissionMode().name().toUpperCase(Locale.ROOT)));
        child.setApprovalHandler(execution.approvalHandler());

        long started = System.currentTimeMillis();
        String result;
        try { result = child.run(instruction); }
        catch (Exception e) {
            log.error("[SubAgent] execution failed", e);
            result = "[A-003] SubAgent execution failed: " + e.getMessage();
        }
        long duration = System.currentTimeMillis() - started;
        events.subAgentCompleted(new SubAgentCompleteEvent(
            result, child.lastRunTurns, child.getEstimatedTokens(), duration));
        return result;
    }
}
