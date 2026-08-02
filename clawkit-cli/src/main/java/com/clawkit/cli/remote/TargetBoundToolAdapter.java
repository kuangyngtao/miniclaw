package com.clawkit.cli.remote;

import com.clawkit.tools.Result;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolBehavior;
import com.clawkit.tools.ToolExecutionPolicy;
import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolExecutionStatus;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolMetadataProvenance;
import com.clawkit.tools.ToolOutputStats;
import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.mcp.McpCallResult;
import com.clawkit.tools.mcp.McpToolDef;
import com.clawkit.tools.remote.RemoteMcpSession;
import com.clawkit.tools.remote.RemoteOutputSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Target-bound decorator for MCP tools from a remote connection.
 *
 * <p>Adds generation checking, connection state validation, and
 * output sanitization on top of the generic {@code McpToolAdapter}.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Verify target generation matches the active connection</li>
 *   <li>Verify connection state is READY (or allowed DEGRADED)</li>
 *   <li>Call the remote tool via {@link RemoteMcpSession}</li>
 *   <li>Enforce output size limits and sanitization</li>
 *   <li>Add {@code remoteEvidence} reference metadata</li>
 * </ol>
 *
 * <p>Design: REMOTE-0 §9.3.
 */
public class TargetBoundToolAdapter implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TargetBoundToolAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String fullName;
    private final String toolName;
    private final String description;
    private final String inputSchema;
    private final RemoteMcpSession session;
    private final long boundGeneration;
    private final RemoteConnectionService service;
    private final ToolMetadata cachedMetadata;

    public TargetBoundToolAdapter(
            String fullName,
            McpToolDef toolDef,
            RemoteMcpSession session,
            long boundGeneration,
            RemoteConnectionService service,
            String description,
            String inputSchema) {
        this.fullName = fullName;
        this.toolName = toolDef.name();
        this.description = description;
        this.inputSchema = inputSchema;
        this.session = session;
        this.boundGeneration = boundGeneration;
        this.service = service;
        this.cachedMetadata = buildMetadata();
    }

    // ── Tool interface ─────────────────────────────────────────────────

    @Override
    public String name() { return fullName; }

    @Override
    public String description() { return description; }

    @Override
    public String inputSchema() { return inputSchema; }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public ToolMetadata metadata() { return cachedMetadata; }

    @Override
    @Deprecated
    public Result<String> execute(String arguments) {
        try {
            JsonNode args = MAPPER.readTree(arguments);
            var req = new ToolExecutionRequest("legacy", fullName, args,
                (com.clawkit.tools.ToolExecutionScope) null);
            ToolExecutionResult result = execute(req);
            if (result.success()) {
                return new Result.Ok<>(result.output());
            }
            return new Result.Err<>(new Result.ErrorInfo(
                result.errorCode() != null ? result.errorCode() : "REMOTE_ERROR",
                result.output()));
        } catch (Exception e) {
            return new Result.Err<>(new Result.ErrorInfo("REMOTE_ERROR", e.getMessage()));
        }
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest req) {
        long start = System.currentTimeMillis();

        // 1. Generation check
        if (!service.isGenerationValid(boundGeneration)) {
            long duration = System.currentTimeMillis() - start;
            return ToolExecutionResult.of(
                req.toolCallId(), fullName,
                "RMT-013: stale remote tool — connection was reset or reconnected",
                ToolExecutionStatus.TOOL_ERROR,
                com.clawkit.tools.ToolError.fatal("RMT-013",
                    "stale generation: " + boundGeneration + " != " + service.generation()),
                duration, ToolOutputStats.EMPTY, null, cachedMetadata, null);
        }

        // 2. Parse arguments
        JsonNode argsNode;
        try {
            String argsJson = req.arguments() != null ? req.arguments().toString() : "{}";
            argsNode = MAPPER.readTree(argsJson);
            if (!argsNode.isObject()) argsNode = MAPPER.createObjectNode();
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return ToolExecutionResult.invalidArguments(
                req.toolCallId(), fullName,
                "Invalid JSON arguments: " + e.getMessage(),
                duration, cachedMetadata);
        }

        // 3. Call remote tool
        try {
            McpCallResult callResult = session.callTool(toolName,
                (com.fasterxml.jackson.databind.node.ObjectNode) argsNode);
            long duration = System.currentTimeMillis() - start;
            byte[] outBytes = callResult.text().getBytes(StandardCharsets.UTF_8);

            // Parse evidence metadata from server response (if present)
            EvidenceMeta meta = parseEvidenceMeta(callResult.text());

            // Client-side sanitization on ALL paths (R0-PR5 second layer)
            String outputText;
            try {
                outputText = RemoteOutputSanitizer.sanitize(callResult.text());
            } catch (RemoteOutputSanitizer.OutputRejectedException e) {
                return ToolExecutionResult.of(
                    req.toolCallId(), fullName,
                    e.code() + ": " + e.getMessage(),
                    ToolExecutionStatus.TOOL_ERROR,
                    com.clawkit.tools.ToolError.fatal(e.code(), e.getMessage()),
                    duration, ToolOutputStats.EMPTY, null, cachedMetadata, null);
            }

            // Build evidence ref with real metadata from response
            String runId = req.scope() != null ? req.scope().runId() : null;
            String evidence = buildEvidenceRef(runId, req.toolCallId(), meta, outBytes.length);

            if (callResult.isError()) {
                String safeOutput = outputText + "\n\n" + evidence;
                return ToolExecutionResult.of(
                    req.toolCallId(), fullName, safeOutput,
                    ToolExecutionStatus.TOOL_ERROR,
                    com.clawkit.tools.ToolError.fatal("MCP_ERROR",
                        truncateForError(outputText)),
                    duration,
                    new ToolOutputStats(outBytes.length, outBytes.length, false),
                    null, cachedMetadata, null);
            }

            return ToolExecutionResult.success(
                req.toolCallId(), fullName,
                outputText + "\n\n" + evidence,
                duration, cachedMetadata);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("[remote] {} failed: {}", fullName, e.getMessage());
            // Sanitize exception message — never pass raw transport errors to model
            String safeMsg = sanitizeExceptionMessage(e);
            return ToolExecutionResult.of(
                req.toolCallId(), fullName,
                "[remote:" + service.activeTargetId() + "] " + toolName + " — " + safeMsg,
                ToolExecutionStatus.TOOL_ERROR,
                com.clawkit.tools.ToolError.fatal("RMT-006", safeMsg),
                duration, ToolOutputStats.EMPTY, null, cachedMetadata, null);
        }
    }

    // ── Metadata ──────────────────────────────────────────────────────

    private ToolMetadata buildMetadata() {
        return new ToolMetadata(
            fullName, description, null, null,
            new ToolBehavior(true, ToolRiskLevel.LOW, false, true, false, false, Set.of()),
            new ToolExecutionPolicy(Duration.ofSeconds(60), 32768,
                ToolExecutionPolicy.OutputTruncation.HEAD, ToolExecutionPolicy.ToolConcurrency.SERIAL),
            ToolMetadataProvenance.mcp("remote", toolName, true)
        );
    }

    // ── Evidence metadata ─────────────────────────────────────────────

    private record EvidenceMeta(String observedAt, String collectedAt,
                                 Boolean current, Boolean truncated,
                                 boolean metadataComplete) {}

    /** Parse server response JSON for evidence metadata fields. */
    private EvidenceMeta parseEvidenceMeta(String responseText) {
        try {
            JsonNode root = MAPPER.readTree(responseText);
            if (root == null || !root.isObject()) {
                return new EvidenceMeta(null, null, null, null, false);
            }
            String observedAt = pathText(root, "observedAt");
            String collectedAt = pathText(root, "collectedAt");
            Boolean current = root.has("current") ? root.get("current").asBoolean() : null;
            Boolean truncated = root.has("audit") && root.get("audit").has("truncated")
                ? root.get("audit").get("truncated").asBoolean() : null;
            boolean complete = observedAt != null && collectedAt != null
                && current != null;
            return new EvidenceMeta(observedAt, collectedAt, current, truncated, complete);
        } catch (Exception e) {
            return new EvidenceMeta(null, null, null, null, false);
        }
    }

    private static String pathText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private String buildEvidenceRef(String runId, String toolCallId,
                                     EvidenceMeta meta, int outputBytes) {
        String targetId = service.activeTargetId() != null
            ? service.activeTargetId() : "unknown";
        String ref = runId != null && !runId.isEmpty() && !"unknown".equals(runId)
            ? "run://" + runId + "/tool/" + toolCallId
            : "call://" + toolCallId;

        StringBuilder sb = new StringBuilder();
        sb.append("{\"remoteEvidence\":{");
        sb.append("\"ref\":\"").append(escapeJson(ref)).append("\",");
        sb.append("\"targetId\":\"").append(escapeJson(targetId)).append("\",");
        sb.append("\"tool\":\"").append(escapeJson(toolName)).append("\",");
        if (meta.observedAt() != null) {
            sb.append("\"observedAt\":\"").append(escapeJson(meta.observedAt())).append("\",");
        }
        if (meta.collectedAt() != null) {
            sb.append("\"collectedAt\":\"").append(escapeJson(meta.collectedAt())).append("\",");
        }
        if (meta.current() != null) {
            sb.append("\"current\":").append(meta.current()).append(",");
        }
        if (meta.truncated() != null) {
            sb.append("\"truncated\":").append(meta.truncated()).append(",");
        }
        sb.append("\"metadataComplete\":").append(meta.metadataComplete()).append(",");
        sb.append("\"outputBytes\":").append(outputBytes);
        sb.append("}}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Sanitization helpers ──────────────────────────────────────────

    /** Sanitize exception messages before they enter model context. */
    private static String sanitizeExceptionMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "remote tool call failed";
        // Strip anything that looks like a path, host, or key reference
        return msg.replaceAll("C:[/\\\\]\\S+", "[path]")
                  .replaceAll("\\d+\\.\\d+\\.\\d+\\.\\d+", "[host]")
                  .replaceAll("\\bopsro@\\S+", "opsro@[host]");
    }

    private static String truncateForError(String text) {
        if (text == null) return "remote error";
        if (text.length() <= 200) return text;
        return text.substring(0, 200) + "...";
    }
}
