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

    /** 握手：initialize → 检查协议版本 → notifications/initialized */
    public void initialize() throws IOException {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "clawkit");
        clientInfo.put("version", "1.0");
        params.putObject("capabilities").putObject("tools");

        String resp = sendRequest("initialize", params);
        JsonNode root = MAPPER.readTree(resp);
        JsonNode result = root.get("result");
        if (result == null) {
            JsonNode error = root.get("error");
            throw new IOException("[MCP:" + serverName + "] initialize failed: "
                + (error != null ? error.toString() : resp));
        }

        String serverVersion = result.path("protocolVersion").asText("");
        if (!PROTOCOL_VERSION.equals(serverVersion) && !serverVersion.isEmpty()) {
            log.warn("[MCP:{}] protocol version mismatch: client={}, server={}",
                serverName, PROTOCOL_VERSION, serverVersion);
        }

        // 发送 initialized 通知（无 id，不等待响应）
        sendNotification("notifications/initialized");
        log.info("[MCP:{}] handshake complete", serverName);
    }

    /** 发现工具列表 */
    public List<McpToolDef> listTools() throws IOException {
        String resp = sendRequest("tools/list", MAPPER.createObjectNode());
        JsonNode root = MAPPER.readTree(resp);
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
        JsonNode result = root.path("result");
        if (result.isMissingNode()) {
            JsonNode error = root.get("error");
            throw new IOException("[MCP:" + serverName + "] " + toolName + " failed: "
                + (error != null ? error.toPrettyString() : root.toString()));
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

        // 检查 JSON-RPC error 字段
        JsonNode respNode = MAPPER.readTree(resp);
        if (respNode.has("error")) {
            JsonNode error = respNode.get("error");
            throw new IOException("[MCP:" + serverName + "] " + method
                + " error (code=" + error.path("code").asInt(-1) + "): "
                + error.path("message").asText("unknown"));
        }
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
