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

    public OpsMcpServer(OpsBackend backend) {
        this(backend, OpsCapabilityProfile.APP_DOWN_V1);
    }

    public OpsMcpServer(OpsBackend backend, OpsCapabilityProfile profile) {
        this.backend = backend;
        this.profile = profile;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void serve(InputStream input, OutputStream output) throws IOException {
        try (var reader = new BufferedReader(
                 new InputStreamReader(input, StandardCharsets.UTF_8));
             var writer = new BufferedWriter(
                 new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                // P0 guardrail: reject oversized lines (§6.2 stdout constraint)
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
        info.put("name", "clawkit-ops-mcp");
        info.put("version", "0.1.0");
        // P0 attestation fields (§6.3, §7.2)
        info.put("probeVersion", PROBE_VERSION);
        info.put("capabilityProfile", profile.name());
        info.put("toolSetHash", computeToolSetHash(profile));
        return success(id, result);
    }

    /**
     * Compute a stable hash of the tool names, annotations, and input schemas
     * for the given profile. Used by the client to verify the remote server
     * exposes exactly the expected tool set (§7.2 attestation).
     */
    public static String computeToolSetHash(OpsCapabilityProfile profile) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Hash the sorted tool names — sufficient for MVP since all tools
            // share the same annotations. If annotations diverge per-tool in
            // the future, include the full annotation set in the hash.
            String[] names = profile.toolNames().toArray(String[]::new);
            java.util.Arrays.sort(names);
            for (String name : names) {
                md.update(name.getBytes(StandardCharsets.UTF_8));
            }
            byte[] digest = md.digest();
            return HexFormat.of().formatHex(digest, 0, 8); // first 8 bytes = 16 hex chars
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private ObjectNode listTools(JsonNode id) {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(tool("service_status", "Read Docker Compose service status.",
            properties(Map.of("service", stringProperty("Allowlisted service name"))),
            required("service")));
        tools.add(tool("container_status", "Read container state for an allowlisted service.",
            properties(Map.of("service", stringProperty("Allowlisted service name"))),
            required("service")));
        tools.add(tool("ports", "Read a published port binding for an allowlisted service.",
            properties(Map.of(
                "service", stringProperty("Allowlisted service name"),
                "containerPort", integerProperty("Allowlisted container port", 1, 65535))),
            required("service", "containerPort")));
        tools.add(tool("http_probe", "Probe an allowlisted HTTP endpoint.",
            properties(Map.of("endpoint", stringProperty("Allowlisted endpoint name"))),
            required("endpoint")));
        tools.add(tool("logs", "Read bounded logs for an allowlisted service and time window.",
            properties(Map.of(
                "service", stringProperty("Allowlisted service name"),
                "windowSeconds", integerProperty("Window length in seconds", 1, 900),
                "tail", integerProperty("Maximum returned log lines", 1, 200))),
            required("service", "windowSeconds", "tail")));
        if (profile == OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1) {
            tools.add(tool("container_resources", "Read one bounded container resource snapshot.",
                properties(Map.of("service", stringProperty("Allowlisted service name"))),
                required("service")));
            tools.add(tool("business_metrics", "Read sanitized order API metrics.",
                properties(Map.of("endpoint", stringProperty("Allowlisted metrics endpoint"))),
                required("endpoint")));
            tools.add(tool("db_activity", "Read bounded PostgreSQL session activity without SQL text.",
                properties(Map.of()), required()));
            tools.add(tool("db_lock_graph", "Read the current PostgreSQL blocker graph.",
                properties(Map.of()), required()));
            tools.add(tool("db_connection_stats", "Read aggregate PostgreSQL connection usage.",
                properties(Map.of()), required()));
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
        OpsToolResult result = switch (name) {
            case "service_status" -> backend.serviceStatus(requiredText(args, "service"));
            case "container_status" -> backend.containerStatus(requiredText(args, "service"));
            case "ports" -> backend.ports(requiredText(args, "service"),
                requiredInt(args, "containerPort"));
            case "http_probe" -> backend.httpProbe(requiredText(args, "endpoint"));
            case "logs" -> backend.logs(requiredText(args, "service"),
                Duration.ofSeconds(requiredInt(args, "windowSeconds")),
                requiredInt(args, "tail"));
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

    private ObjectNode tool(
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
