package com.clawkit.tools.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.clawkit.tools.control.ExecutionControl;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MCP JSON-RPC 2.0 客户端。封装握手、工具发现、工具调用。 */
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpTransport transport;
    private final String serverName;
    private final AtomicLong nextId = new AtomicLong(1);

    public McpClient(McpTransport transport, String serverName) {
        this.transport = transport;
        this.serverName = serverName;
    }

    /**
     * Handshake: initialize → check protocol version → notifications/initialized.
     *
     * @return the server's initialize result, including serverInfo for
     *         downstream attestation. Callers who don't need attestation
     *         can ignore the return value.
     */
    public McpInitializeResult initialize() throws IOException {
        return initialize(ExecutionControl.none());
    }

    /** initialize with cancellation/timeout support. */
    public McpInitializeResult initialize(ExecutionControl control) throws IOException {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "clawkit");
        clientInfo.put("version", "1.0");
        params.putObject("capabilities").putObject("tools");

        String resp = sendRequest("initialize", params, control);
        JsonNode root = MAPPER.readTree(resp);
        JsonNode result = root.get("result");
        if (result == null) {
            JsonNode error = root.get("error");
            throw new IOException("[MCP:" + serverName + "] initialize failed: "
                + (error != null ? error.toString() : resp));
        }

        String serverProtocolVersion = result.path("protocolVersion").asText("");

        // Strict: protocol version MUST match (§4.1)
        if (!PROTOCOL_VERSION.equals(serverProtocolVersion)) {
            throw new IOException("[MCP:" + serverName
                + "] protocol version mismatch: expected " + PROTOCOL_VERSION
                + " but got " + (serverProtocolVersion.isEmpty() ? "<none>" : serverProtocolVersion));
        }

        JsonNode info = result.path("serverInfo");
        String name = info.path("name").asText("");
        String version = info.path("version").asText("");

        // 发送 initialized 通知（无 id，不等待响应）
        sendNotification("notifications/initialized");
        log.info("[MCP:{}] handshake complete — {}/{}", serverName, name, version);

        return new McpInitializeResult(serverProtocolVersion, name, version,
            info.isMissingNode() ? MAPPER.createObjectNode() : info);
    }

    /** 发现工具列表 */
    public List<McpToolDef> listTools() throws IOException {
        String resp = sendRequest("tools/list", MAPPER.createObjectNode());
        JsonNode root = MAPPER.readTree(resp);

        // JSON-RPC error → throw, do NOT silently return empty list
        if (root.has("error")) {
            JsonNode error = root.get("error");
            throw new IOException("[MCP:" + serverName + "] tools/list"
                + " error (code=" + error.path("code").asInt(-1) + "): "
                + error.path("message").asText("unknown"));
        }

        JsonNode tools = root.path("result").path("tools");
        if (!tools.isArray()) return List.of();

        List<McpToolDef> result = new ArrayList<>();
        for (JsonNode t : tools) {
            String name = t.path("name").asText();
            String desc = t.path("description").asText("");
            JsonNode schema = t.path("inputSchema");
            // 优先用 description 而非 title
            if (desc.isEmpty()) {
                desc = t.path("title").asText("");
            }
            JsonNode annotations = t.path("annotations");
            JsonNode outputSchema = t.path("outputSchema");
            result.add(new McpToolDef(name, desc, schema,
                annotations.isMissingNode() ? null : annotations,
                outputSchema.isMissingNode() ? null : outputSchema));
        }
        return result;
    }

    /** 调用工具，返回结构化 McpCallResult（V2：保留 isError + content items） */
    public McpCallResult callTool(String toolName, JsonNode arguments) throws IOException {
        return callTool(toolName, arguments, ExecutionControl.none());
    }

    public McpCallResult callTool(String toolName, JsonNode arguments,
                                  ExecutionControl control) throws IOException {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments != null ? arguments : MAPPER.createObjectNode());

        String resp = sendRequest("tools/call", params, control);
        JsonNode root = MAPPER.readTree(resp);

        // JSON-RPC error → tool-level error, NOT transport failure (§5.5)
        if (root.has("error")) {
            JsonNode error = root.get("error");
            return McpCallResult.error(
                "JSON-RPC error (code=" + error.path("code").asInt(-1) + "): "
                + error.path("message").asText("unknown"), List.of());
        }

        JsonNode result = root.path("result");
        if (result.isMissingNode()) {
            throw new IOException("[MCP:" + serverName + "] " + toolName
                + " failed: malformed response (no result, no error): "
                + root.toString());
        }

        // 检查 isError
        boolean isError = result.path("isError").asBoolean(false);

        JsonNode content = result.path("content");
        if (!content.isArray()) {
            String text = content.asText();
            return isError ? McpCallResult.error(text, List.of()) : McpCallResult.success(text, List.of());
        }

        // 收集所有 content items
        List<JsonNode> contentItems = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : content) {
            contentItems.add(item);
            String type = item.path("type").asText("text");
            if ("text".equals(type)) {
                sb.append(item.path("text").asText());
            } else {
                sb.append("[mcp: non-text content type=").append(type).append("]");
            }
        }
        return isError ? McpCallResult.error(sb.toString(), contentItems)
                       : McpCallResult.success(sb.toString(), contentItems);
    }

    private String sendRequest(String method, ObjectNode params) throws IOException {
        return sendRequest(method, params, ExecutionControl.none());
    }

    private String sendRequest(String method, ObjectNode params,
                               ExecutionControl control) throws IOException {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", nextId.getAndIncrement());
        request.put("method", method);
        request.set("params", params);

        String body = MAPPER.writeValueAsString(request);
        log.debug("[MCP:{}] → {}", serverName, method);
        String resp = transport.send(body, control);
        log.debug("[MCP:{}] ← {}", serverName, method);

        // Do NOT throw for JSON-RPC error here — let callers handle
        // application-level errors (e.g., invalid params) vs transport errors.
        return resp;
    }

    private void sendNotification(String method) throws IOException {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        transport.send(MAPPER.writeValueAsString(notification));
    }

    public McpTransport transport() { return transport; }
    public String serverName() { return serverName; }
}
