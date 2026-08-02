package com.clawkit.ops.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OpsMcpServer {

    public static final String PROTOCOL_VERSION = "2024-11-05";
    public static final String PROBE_VERSION = "1";
    public static final int MAX_LINE_BYTES = 65_536; // 64 KiB
    public static final java.util.Set<String> TOOL_NAMES = java.util.Set.of(
        "service_status", "container_status", "ports", "http_probe", "logs");

    private final OpsBackend backend;
    private final OpsCapabilityProfile profile;
    private final ObjectMapper mapper;
    private final String serverName;
    private final DockerFixBackend fixBackend;

    public OpsMcpServer(OpsBackend backend) {
        this(backend, OpsCapabilityProfile.APP_DOWN_V1);
    }

    public OpsMcpServer(OpsBackend backend, OpsCapabilityProfile profile) {
        this(backend, profile, null);
    }

    /**
     * Full constructor with optional DockerFixBackend for FIX_ORDER_API_V1 profile.
     */
    public OpsMcpServer(OpsBackend backend, OpsCapabilityProfile profile,
                        DockerFixBackend fixBackend) {
        this.backend = backend;
        this.profile = profile;
        this.fixBackend = fixBackend;
        this.serverName = profile == OpsCapabilityProfile.FIX_ORDER_API_V1
            ? "clawkit-ops-fix" : "clawkit-ops-mcp";
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public String serverName() { return serverName; }

    public void serve(InputStream input, OutputStream output) throws IOException {
        try (var reader = new BufferedReader(
                 new InputStreamReader(input, StandardCharsets.UTF_8));
             var writer = new BufferedWriter(
                 new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                int byteLength = line.getBytes(StandardCharsets.UTF_8).length;
                if (byteLength > MAX_LINE_BYTES) {
                    write(writer, error(null, -32600,
                        "request exceeds maximum size of " + MAX_LINE_BYTES + " bytes"));
                    continue;
                }
                JsonNode request;
                try {
                    request = mapper.readTree(line);
                } catch (Exception e) {
                    write(writer, error(null, -32700, "parse error"));
                    continue;
                }
                JsonNode id = request.get("id");
                if (id == null) {
                    continue;
                }
                try {
                    write(writer, handle(id, request.path("method").asText(),
                        request.path("params")));
                } catch (IllegalArgumentException e) {
                    write(writer, error(id, -32602, e.getMessage()));
                } catch (Exception e) {
                    write(writer, error(id, -32603, "internal error: " + e.getMessage()));
                }
            }
        }
    }

    ObjectNode handle(JsonNode id, String method, JsonNode params) {
        return switch (method) {
            case "initialize" -> initialize(id);
            case "ping" -> success(id, mapper.createObjectNode());
            case "tools/list" -> listTools(id);
            case "tools/call" -> callTool(id, params);
            default -> error(id, -32601, "method not found: " + method);
        };
    }

    private ObjectNode initialize(JsonNode id) {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", serverName);
        info.put("version", "0.1.0");
        info.put("probeVersion", PROBE_VERSION);
        info.put("capabilityProfile", profile.name());
        info.put("toolSetHash", computeToolSetHash(profile));
        return success(id, result);
    }

    public static String computeToolSetHash(OpsCapabilityProfile profile) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String[] names = profile.toolNames().toArray(String[]::new);
            java.util.Arrays.sort(names);
            for (String name : names) {
                md.update(name.getBytes(StandardCharsets.UTF_8));
            }
            byte[] digest = md.digest();
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Compute the expected tool-contract hash for a profile.
     * Builds the canonical tool definitions (same as listTools) and hashes them
     * with {@link com.clawkit.tools.remote.ToolContractHash}.
     */
    // Shared static mapper for contract hash computation (matches instance mapper config)
    private static final ObjectMapper STATIC_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Compute the expected tool-contract hash for a profile by actually
     * running the same tool-definition logic as {@code listTools()}.
     *
     * <p>Uses a stub backend to construct the exact tool definitions,
     * then hashes them. This guarantees the hash matches what a real
     * server with this profile will produce.
     */
    public static String computeExpectedToolContractHash(OpsCapabilityProfile profile) {
        // Use a stub backend — the tool DEFINITIONS are profile-dependent,
        // not backend-dependent. listTools() builds the same tools regardless.
        var server = new OpsMcpServer(new OpsBackend() {
            public OpsToolResult serviceStatus(String s) { return stubResult("service_status"); }
            public OpsToolResult containerStatus(String s) { return stubResult("container_status"); }
            public OpsToolResult ports(String s, int p) { return stubResult("ports"); }
            public OpsToolResult httpProbe(String e) { return stubResult("http_probe"); }
            public OpsToolResult logs(String s, java.time.Duration w, int t) { return stubResult("logs"); }
            public OpsToolResult containerResources(String s) { return stubResult("container_resources"); }
            public OpsToolResult businessMetrics(String e) { return stubResult("business_metrics"); }
            public OpsToolResult dbActivity() { return stubResult("db_activity"); }
            public OpsToolResult dbLockGraph() { return stubResult("db_lock_graph"); }
            public OpsToolResult dbConnectionStats() { return stubResult("db_connection_stats"); }
        }, profile);

        // Build the tools list via the same logic as listTools(),
        // parse into McpToolDef, and hash them.
        ObjectNode listResult = server.buildToolsListJson();
        JsonNode toolsArray = listResult.get("tools");
        var tools = new java.util.ArrayList<com.clawkit.tools.mcp.McpToolDef>();
        if (toolsArray != null && toolsArray.isArray()) {
            for (JsonNode t : toolsArray) {
                tools.add(new com.clawkit.tools.mcp.McpToolDef(
                    t.path("name").asText(),
                    t.path("description").asText(""),
                    t.path("inputSchema"),
                    t.has("annotations") ? t.get("annotations") : null,
                    t.has("outputSchema") ? t.get("outputSchema") : null));
            }
        }
        return com.clawkit.tools.remote.ToolContractHash.computeFromMcpTools(tools);
    }

    /** Package-visible: build the tools/list JSON for hash computation. */
    ObjectNode buildToolsListJson() {
        // Simulate the same logic as listTools()
        var mapper2 = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ArrayNode toolsArray = mapper2.createArrayNode();

        if (profile == OpsCapabilityProfile.FIX_ORDER_API_V1) {
            toolsArray.add(fixTool("restart_service",
                "Restart an allowlisted Docker Compose service. Only order-api is allowed.",
                properties(Map.of("serviceId", stringProperty("Service identifier (must be order-api)"))),
                required("serviceId")));
        } else {
            toolsArray.add(roTool("service_status", "Read Docker Compose service status.",
                properties(Map.of("service", stringProperty("Allowlisted service name"))),
                required("service")));
            toolsArray.add(roTool("container_status", "Read container state for an allowlisted service.",
                properties(Map.of("service", stringProperty("Allowlisted service name"))),
                required("service")));
            toolsArray.add(roTool("ports", "Read a published port binding for an allowlisted service.",
                properties(Map.of(
                    "service", stringProperty("Allowlisted service name"),
                    "containerPort", integerProperty("Allowlisted container port", 1, 65535))),
                required("service", "containerPort")));
            toolsArray.add(roTool("http_probe", "Probe an allowlisted HTTP endpoint.",
                properties(Map.of("endpoint", stringProperty("Allowlisted endpoint name"))),
                required("endpoint")));
            toolsArray.add(roTool("logs", "Read bounded logs for an allowlisted service and time window.",
                properties(Map.of(
                    "service", stringProperty("Allowlisted service name"),
                    "windowSeconds", integerProperty("Window length in seconds", 1, 900),
                    "tail", integerProperty("Maximum returned log lines", 1, 200))),
                required("service", "windowSeconds", "tail")));
            if (profile == OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1) {
                toolsArray.add(roTool("container_resources",
                    "Read one bounded container resource snapshot.",
                    properties(Map.of("service", stringProperty("Allowlisted service name"))),
                    required("service")));
                toolsArray.add(roTool("business_metrics", "Read sanitized order API metrics.",
                    properties(Map.of("endpoint", stringProperty("Allowlisted metrics endpoint"))),
                    required("endpoint")));
                toolsArray.add(roTool("db_activity",
                    "Read bounded PostgreSQL session activity without SQL text.",
                    properties(Map.of()), required()));
                toolsArray.add(roTool("db_lock_graph", "Read the current PostgreSQL blocker graph.",
                    properties(Map.of()), required()));
                toolsArray.add(roTool("db_connection_stats",
                    "Read aggregate PostgreSQL connection usage.",
                    properties(Map.of()), required()));
            }
        }

        ObjectNode result = mapper2.createObjectNode();
        result.set("tools", toolsArray);
        return result;
    }

    /** Sanitize a log result, handling both textual and ObjectNode data. */
    private OpsToolResult sanitizeLogResult(OpsToolResult raw) {
        JsonNode data = raw.data();
        if (data.isTextual()) {
            var sanitized = LogSanitizer.sanitizeAll(data.asText());
            return new OpsToolResult(raw.tool(), raw.target(),
                raw.observedAt(), raw.collectedAt(), raw.current(), true,
                mapper.getNodeFactory().textNode(sanitized.text()),
                null, null, raw.audit());
        }
        if (data.isObject()) {
            // Deep-copy, sanitize data.text, preserve other fields
            ObjectNode copy = data.deepCopy();
            JsonNode textNode = copy.get("text");
            if (textNode != null && textNode.isTextual()) {
                var sanitized = LogSanitizer.sanitizeAll(textNode.asText());
                copy.put("text", sanitized.text());
                copy.put("redactionApplied", sanitized.redactionApplied());
                copy.put("redactedMatches", sanitized.redactedMatches());
            }
            return new OpsToolResult(raw.tool(), raw.target(),
                raw.observedAt(), raw.collectedAt(), raw.current(), true,
                copy, null, null, raw.audit());
        }
        return null; // unhandled shape — pass through
    }

    private static OpsToolResult stubResult(String tool) {
        return new OpsToolResult(tool, "stub", java.time.Instant.EPOCH, java.time.Instant.EPOCH,
            true, true, STATIC_MAPPER.createObjectNode(), null, null,
            new OpsToolResult.Audit("stub", 0, 0, 0, 0, false));
    }

    private ObjectNode listTools(JsonNode id) {
        ArrayNode tools = mapper.createArrayNode();

        if (profile == OpsCapabilityProfile.FIX_ORDER_API_V1) {
            // ── FIX profile: only restart_service ──
            tools.add(fixTool("restart_service",
                "Restart an allowlisted Docker Compose service. Only order-api is allowed.",
                properties(Map.of(
                    "serviceId", stringProperty("Service identifier (must be order-api)"))),
                required("serviceId")));
        } else {
            // ── Read-only profiles ──
            tools.add(roTool("service_status", "Read Docker Compose service status.",
                properties(Map.of("service", stringProperty("Allowlisted service name"))),
                required("service")));
            tools.add(roTool("container_status", "Read container state for an allowlisted service.",
                properties(Map.of("service", stringProperty("Allowlisted service name"))),
                required("service")));
            tools.add(roTool("ports", "Read a published port binding for an allowlisted service.",
                properties(Map.of(
                    "service", stringProperty("Allowlisted service name"),
                    "containerPort", integerProperty("Allowlisted container port", 1, 65535))),
                required("service", "containerPort")));
            tools.add(roTool("http_probe", "Probe an allowlisted HTTP endpoint.",
                properties(Map.of("endpoint", stringProperty("Allowlisted endpoint name"))),
                required("endpoint")));
            tools.add(roTool("logs", "Read bounded logs for an allowlisted service and time window.",
                properties(Map.of(
                    "service", stringProperty("Allowlisted service name"),
                    "windowSeconds", integerProperty("Window length in seconds", 1, 900),
                    "tail", integerProperty("Maximum returned log lines", 1, 200))),
                required("service", "windowSeconds", "tail")));
            if (profile == OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1) {
                tools.add(roTool("container_resources", "Read one bounded container resource snapshot.",
                    properties(Map.of("service", stringProperty("Allowlisted service name"))),
                    required("service")));
                tools.add(roTool("business_metrics", "Read sanitized order API metrics.",
                    properties(Map.of("endpoint", stringProperty("Allowlisted metrics endpoint"))),
                    required("endpoint")));
                tools.add(roTool("db_activity", "Read bounded PostgreSQL session activity without SQL text.",
                    properties(Map.of()), required()));
                tools.add(roTool("db_lock_graph", "Read the current PostgreSQL blocker graph.",
                    properties(Map.of()), required()));
                tools.add(roTool("db_connection_stats", "Read aggregate PostgreSQL connection usage.",
                    properties(Map.of()), required()));
            }
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", tools);
        return success(id, result);
    }

    private ObjectNode callTool(JsonNode id, JsonNode params) {
        String name = requiredText(params, "name");
        if (!profile.toolNames().contains(name)) {
            return error(id, -32602, "unknown or prohibited tool: " + name);
        }
        JsonNode args = params.path("arguments");

        if (profile == OpsCapabilityProfile.FIX_ORDER_API_V1) {
            if (!"restart_service".equals(name)) {
                return error(id, -32602, "fix profile only supports restart_service");
            }
            String serviceId = requiredText(args, "serviceId");
            if (!"order-api".equals(serviceId)) {
                return error(id, -32602, "fix backend only allows order-api, got: " + serviceId);
            }
            if (fixBackend == null) {
                return error(id, -32603, "fix backend not configured");
            }
            OpsToolResult result = fixBackend.restartService(serviceId);
            JsonNode structured = mapper.valueToTree(result);
            ObjectNode callResult = mapper.createObjectNode();
            callResult.put("isError", !result.success());
            callResult.set("structuredContent", structured);
            ObjectNode text = mapper.createObjectNode();
            text.put("type", "text");
            try {
                text.put("text", mapper.writeValueAsString(result));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            callResult.putArray("content").add(text);
            return success(id, callResult);
        }

        // Read-only tool dispatch
        OpsToolResult result = switch (name) {
            case "service_status" -> backend.serviceStatus(requiredText(args, "service"));
            case "container_status" -> backend.containerStatus(requiredText(args, "service"));
            case "ports" -> backend.ports(requiredText(args, "service"),
                requiredInt(args, "containerPort"));
            case "http_probe" -> backend.httpProbe(requiredText(args, "endpoint"));
            case "logs" -> {
                var raw = backend.logs(requiredText(args, "service"),
                    Duration.ofSeconds(requiredInt(args, "windowSeconds")),
                    requiredInt(args, "tail"));
                // Server-side first-layer sanitization (REMOTE-0 §12.1)
                if (raw.success() && raw.data() != null) {
                    OpsToolResult sanitized = sanitizeLogResult(raw);
                    if (sanitized != null) yield sanitized;
                }
                yield raw;
            }
            case "container_resources" -> backend.containerResources(requiredText(args, "service"));
            case "business_metrics" -> backend.businessMetrics(requiredText(args, "endpoint"));
            case "db_activity" -> backend.dbActivity();
            case "db_lock_graph" -> backend.dbLockGraph();
            case "db_connection_stats" -> backend.dbConnectionStats();
            default -> throw new IllegalStateException("unreachable");
        };
        JsonNode structured = mapper.valueToTree(result);
        ObjectNode callResult = mapper.createObjectNode();
        callResult.put("isError", !result.success());
        callResult.set("structuredContent", structured);
        ObjectNode text = mapper.createObjectNode();
        text.put("type", "text");
        try {
            text.put("text", mapper.writeValueAsString(result));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        callResult.putArray("content").add(text);
        return success(id, callResult);
    }

    // ── Read-only tool builder (readOnlyHint=true, destructiveHint=false, LOW risk) ──
    private ObjectNode roTool(
        String name, String description, ObjectNode properties, ArrayNode required
    ) {
        ObjectNode tool = roBaseTool(name, description, properties, required);
        ObjectNode annotations = tool.putObject("annotations");
        annotations.put("readOnlyHint", true);
        annotations.put("destructiveHint", false);
        annotations.put("idempotentHint", true);
        annotations.put("openWorldHint", false);
        ObjectNode metadata = tool.putObject("_meta");
        metadata.put("clawkit/riskLevel", "LOW");
        metadata.put("clawkit/timeoutMs", 10_000);
        metadata.put("clawkit/maxOutputBytes", 32_768);
        metadata.putArray("clawkit/auditFields")
            .add("backend")
            .add("durationMs")
            .add("timeoutMs")
            .add("totalOutputBytes")
            .add("returnedOutputBytes")
            .add("truncated");
        return tool;
    }

    // ── Fix tool builder (destructiveHint=true, readOnlyHint=false, HIGH risk) ──
    private ObjectNode fixTool(
        String name, String description, ObjectNode properties, ArrayNode required
    ) {
        ObjectNode tool = roBaseTool(name, description, properties, required);
        ObjectNode annotations = tool.putObject("annotations");
        annotations.put("readOnlyHint", false);
        annotations.put("destructiveHint", true);
        annotations.put("idempotentHint", false);
        annotations.put("openWorldHint", false);
        ObjectNode metadata = tool.putObject("_meta");
        metadata.put("clawkit/riskLevel", "HIGH");
        metadata.put("clawkit/timeoutMs", 30_000);
        metadata.put("clawkit/maxOutputBytes", 32_768);
        metadata.putArray("clawkit/auditFields")
            .add("backend")
            .add("durationMs")
            .add("timeoutMs")
            .add("totalOutputBytes")
            .add("returnedOutputBytes")
            .add("truncated");
        return tool;
    }

    /** Shared tool structure without annotations/metadata (added by roTool/fixTool). */
    private ObjectNode roBaseTool(
        String name, String description, ObjectNode properties, ArrayNode required
    ) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", required);
        schema.put("additionalProperties", false);
        ObjectNode output = tool.putObject("outputSchema");
        output.put("type", "object");
        ObjectNode outputProperties = output.putObject("properties");
        outputProperties.set("tool", typed("string"));
        outputProperties.set("target", typed("string"));
        outputProperties.set("observedAt", typed("string"));
        outputProperties.set("collectedAt", typed("string"));
        outputProperties.set("current", typed("boolean"));
        outputProperties.set("success", typed("boolean"));
        outputProperties.set("data", typed("object"));
        outputProperties.set("errorCode", nullableString());
        outputProperties.set("error", nullableString());
        outputProperties.set("audit", typed("object"));
        output.set("required", required(
            "tool", "target", "observedAt", "collectedAt", "current",
            "success", "data", "audit"));
        return tool;
    }

    private ObjectNode properties(Map<String, ObjectNode> fields) {
        ObjectNode result = mapper.createObjectNode();
        new LinkedHashMap<>(fields).forEach(result::set);
        return result;
    }

    private ObjectNode stringProperty(String description) {
        ObjectNode result = mapper.createObjectNode();
        result.put("type", "string");
        result.put("description", description);
        result.put("minLength", 1);
        return result;
    }

    private ObjectNode integerProperty(String description, int min, int max) {
        ObjectNode result = mapper.createObjectNode();
        result.put("type", "integer");
        result.put("description", description);
        result.put("minimum", min);
        result.put("maximum", max);
        return result;
    }

    private ObjectNode typed(String type) {
        ObjectNode result = mapper.createObjectNode();
        result.put("type", type);
        return result;
    }

    private ObjectNode nullableString() {
        ObjectNode result = mapper.createObjectNode();
        result.putArray("type").add("string").add("null");
        return result;
    }

    private ArrayNode required(String... names) {
        ArrayNode result = mapper.createArrayNode();
        for (String name : names) result.add(name);
        return result;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.intValue();
    }

    private ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null) response.putNull("id"); else response.set("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private void write(BufferedWriter writer, JsonNode response) throws IOException {
        writer.write(mapper.writeValueAsString(response));
        writer.newLine();
        writer.flush();
    }
}
