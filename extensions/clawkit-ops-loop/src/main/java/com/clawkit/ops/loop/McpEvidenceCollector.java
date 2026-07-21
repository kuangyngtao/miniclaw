package com.clawkit.ops.loop;

import com.clawkit.observability.FileRunRecorder;
import com.clawkit.observability.RunCompletedPayload;
import com.clawkit.observability.RunStartedPayload;
import com.clawkit.observability.RunStatus;
import com.clawkit.observability.ToolCompletedPayload;
import com.clawkit.observability.ToolInvokedPayload;
import com.clawkit.ops.mcp.OpsMcpServer;
import com.clawkit.ops.mcp.OpsToolResult;
import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.mcp.McpClient;
import com.clawkit.tools.mcp.McpToolDef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class McpEvidenceCollector {
    private final McpClient client;
    private final FileRunRecorder recorder;
    private final Clock clock;
    private final ObjectMapper mapper =
        new ObjectMapper().registerModule(new JavaTimeModule());
    private final AtomicInteger sequence = new AtomicInteger();
    private final List<String> invokedTools = new ArrayList<>();

    public McpEvidenceCollector(McpClient client, FileRunRecorder recorder, Clock clock) {
        this.client = client;
        this.recorder = recorder;
        this.clock = clock;
    }

    public void validateCapabilityBoundary() throws Exception {
        List<McpToolDef> tools = client.listTools();
        Set<String> names = tools.stream().map(McpToolDef::name)
            .collect(java.util.stream.Collectors.toSet());
        if (!names.equals(OpsMcpServer.TOOL_NAMES)) {
            throw new IllegalStateException("unexpected OPS MCP tool set: " + names);
        }
        for (McpToolDef tool : tools) {
            JsonNode a = tool.annotations();
            if (a == null
                || !a.path("readOnlyHint").asBoolean(false)
                || a.path("destructiveHint").asBoolean(true)
                || a.path("openWorldHint").asBoolean(true)) {
                throw new IllegalStateException(
                    "unsafe or incomplete MCP annotations for " + tool.name());
            }
        }
    }

    public EvidenceBundle collect(String incidentId, String runId) {
        Instant start = clock.instant();
        recorder.record(new RunStartedPayload(
            "OPS-0A read-only evidence collection", "isolated-ops-workspace",
            "deterministic-collector", "PLAN", "OFF", "OPS_DIAGNOSIS"),
            runId, null, null, start);
        try {
            List<Evidence> evidence = List.of(
                call(incidentId, runId, EvidenceType.SERVICE_STATUS,
                    "compose/gateway", "service_status", args("service", "gateway")),
                call(incidentId, runId, EvidenceType.SERVICE_STATUS,
                    "compose/demo-api", "service_status", args("service", "demo-api")),
                call(incidentId, runId, EvidenceType.CONTAINER_STATUS,
                    "container/gateway", "container_status", args("service", "gateway")),
                call(incidentId, runId, EvidenceType.CONTAINER_STATUS,
                    "container/demo-api", "container_status", args("service", "demo-api")),
                call(incidentId, runId, EvidenceType.PORT_BINDING,
                    "compose/gateway:80", "ports", args(
                        "service", "gateway", "containerPort", 80)),
                call(incidentId, runId, EvidenceType.HTTP_PROBE,
                    "endpoint/gateway-health", "http_probe",
                    args("endpoint", "gateway-health")),
                call(incidentId, runId, EvidenceType.LOGS,
                    "container/gateway", "logs", args(
                        "service", "gateway", "windowSeconds", 300, "tail", 100))
            );
            recorder.record(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
                runId, null, null, clock.instant());
            return new EvidenceBundle(incidentId, runId, clock.instant(), evidence);
        } catch (RuntimeException e) {
            recorder.record(new RunCompletedPayload(
                    RunStatus.EXECUTION_FAILED, "OPS_EVIDENCE_FAILED", e.getMessage()),
                runId, null, null, clock.instant());
            throw e;
        }
    }

    public List<String> invokedTools() {
        return List.copyOf(invokedTools);
    }

    private Evidence call(
        String incidentId,
        String runId,
        EvidenceType type,
        String scope,
        String tool,
        ObjectNode arguments
    ) {
        int n = sequence.incrementAndGet();
        String toolCallId = "ops-call-" + n;
        String fullName = "mcp__ops__" + tool;
        invokedTools.add(tool);
        Instant invokedAt = clock.instant();
        recorder.record(new ToolInvokedPayload(
                toolCallId, fullName, safeArgSummary(arguments),
                false, true, ToolRiskLevel.LOW, false, false),
            runId, null, n, invokedAt);
        try {
            var callResult = client.callTool(tool, arguments);
            OpsToolResult result = mapper.readValue(callResult.text(), OpsToolResult.class);
            int bytes = callResult.text().getBytes(StandardCharsets.UTF_8).length;
            recorder.record(new ToolCompletedPayload(
                    toolCallId, fullName, result.success(),
                    result.audit().durationMs(), bytes,
                    result.audit().truncated(), false, null,
                    result.errorCode(), result.error()),
                runId, null, n, clock.instant());

            ObjectNode fact = mapper.createObjectNode();
            fact.put("success", result.success());
            fact.set("data", result.data());
            if (result.errorCode() != null) fact.put("errorCode", result.errorCode());
            if (result.error() != null) fact.put("error", result.error());
            return new Evidence(
                "e-" + n, incidentId, type, "mcp:ops/" + tool,
                result.observedAt(), result.collectedAt(), scope,
                Evidence.Kind.FACT, fact,
                "run://" + runId + "/tool/" + toolCallId,
                result.current() ? Evidence.Freshness.CURRENT : Evidence.Freshness.HISTORICAL,
                Evidence.Redaction.NONE);
        } catch (Exception e) {
            recorder.record(new ToolCompletedPayload(
                    toolCallId, fullName, false,
                    Duration.between(invokedAt, clock.instant()).toMillis(), 0,
                    false, false, null, "MCP_CALL_FAILED", e.getMessage()),
                runId, null, n, clock.instant());
            throw new IllegalStateException("OPS MCP call failed: " + tool, e);
        }
    }

    private ObjectNode args(Object... pairs) {
        ObjectNode result = mapper.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = (String) pairs[i];
            Object value = pairs[i + 1];
            if (value instanceof Integer integer) {
                result.put(key, integer);
            } else {
                result.put(key, String.valueOf(value));
            }
        }
        return result;
    }

    private static String safeArgSummary(ObjectNode arguments) {
        Map<String, String> summary = new LinkedHashMap<>();
        arguments.fields().forEachRemaining(e ->
            summary.put(e.getKey(), e.getValue().asText()));
        return summary.toString();
    }
}
