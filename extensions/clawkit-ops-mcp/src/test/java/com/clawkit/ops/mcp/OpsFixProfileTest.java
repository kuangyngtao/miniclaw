package com.clawkit.ops.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the FIX_ORDER_API_V1 profile in OpsMcpServer.
 */
class OpsFixProfileTest {

    private OpsMcpServer server;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        OpsTargetConfig config = new OpsTargetConfig(
            java.nio.file.Path.of("/nonexistent/compose.yaml"),
            "clawkit-ops-r6",
            java.util.Set.of("order-api"),
            Map.of("order-api", java.util.Set.of(8080)),
            Map.of("order-api-ready", java.net.URI.create("http://localhost:8080/ready")),
            java.time.Duration.ofSeconds(10),
            java.time.Duration.ofMinutes(15),
            32768, 200);

        CommandExecutor executor = (cmd, env, timeout, maxBytes) ->
            new CommandResult(0, "{\"success\":true}", "", false, false, 20);

        DockerFixBackend fixBackend = new DockerFixBackend(config, executor);
        server = new OpsMcpServer(null, OpsCapabilityProfile.FIX_ORDER_API_V1, fixBackend);
        mapper = new ObjectMapper();
    }

    @Test
    void shouldReturnCorrectServerNameForFixProfile() {
        assertThat(server.serverName()).isEqualTo("clawkit-ops-fix");
    }

    @Test
    void shouldInitializeWithFixProfile() throws Exception {
        String response = sendRequest(method("initialize", 1));
        JsonNode r = mapper.readTree(response);

        assertThat(r.path("result").path("serverInfo").path("name").asText())
            .isEqualTo("clawkit-ops-fix");
        assertThat(r.path("result").path("serverInfo").path("capabilityProfile").asText())
            .isEqualTo("FIX_ORDER_API_V1");
        assertThat(r.path("result").path("serverInfo").path("toolSetHash").asText())
            .isNotEmpty();
    }

    @Test
    void shouldListOnlyRestartService() throws Exception {
        String response = sendRequest(method("tools/list", 2));
        JsonNode r = mapper.readTree(response);

        JsonNode tools = r.path("result").path("tools");
        assertThat(tools.size()).isEqualTo(1);
        assertThat(tools.get(0).path("name").asText()).isEqualTo("restart_service");
        assertThat(tools.get(0).path("annotations").path("destructiveHint").asBoolean()).isTrue();
        assertThat(tools.get(0).path("annotations").path("readOnlyHint").asBoolean()).isFalse();
    }

    @Test
    void shouldAllowRestartServiceForOrderApi() throws Exception {
        String response = sendRequest(toolCall("restart_service", Map.of("serviceId", "order-api"), 3));
        JsonNode r = mapper.readTree(response);

        assertThat(r.path("result").has("structuredContent")).isTrue();
        assertThat(r.has("error")).isFalse();
    }

    @Test
    void shouldRejectRestartServiceForNonOrderApi() throws Exception {
        String response = sendRequest(toolCall("restart_service", Map.of("serviceId", "postgres"), 4));
        JsonNode r = mapper.readTree(response);

        // Server-side validation rejects non-order-api
        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(r.path("error").path("message").asText()).contains("order-api");
    }

    @Test
    void shouldRejectNonRestartServiceToolInFixProfile() throws Exception {
        String response = sendRequest(toolCall("service_status", Map.of("service", "order-api"), 5));
        JsonNode r = mapper.readTree(response);

        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(r.path("error").path("message").asText()).contains("unknown");
    }

    @Test
    void shouldPing() throws Exception {
        String response = sendRequest(method("ping", 6));
        JsonNode r = mapper.readTree(response);
        assertThat(r.has("result")).isTrue();
    }

    @Test
    void shouldRejectUnknownMethod() throws Exception {
        String response = sendRequest(method("tools/execute", 7));
        JsonNode r = mapper.readTree(response);
        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void toolSetHashShouldBeDeterministic() {
        String h1 = OpsMcpServer.computeToolSetHash(OpsCapabilityProfile.FIX_ORDER_API_V1);
        String h2 = OpsMcpServer.computeToolSetHash(OpsCapabilityProfile.FIX_ORDER_API_V1);
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).isNotEmpty();
    }

    @Test
    void fixToolShouldHaveDestructiveAnnotation() throws Exception {
        String response = sendRequest(method("tools/list", 8));
        JsonNode r = mapper.readTree(response);
        JsonNode firstTool = r.path("result").path("tools").get(0);

        assertThat(firstTool.path("annotations").path("destructiveHint").asBoolean()).isTrue();
        assertThat(firstTool.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
    }

    // ── helpers ──

    private String sendRequest(ObjectNode request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String line = mapper.writeValueAsString(request) + "\n";
        ByteArrayInputStream in = new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8));
        server.serve(in, out);
        // Read first non-blank line from output
        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");
        for (String l : lines) {
            if (!l.isBlank()) return l;
        }
        return "";
    }

    private ObjectNode method(String method, int id) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        req.put("id", id);
        return req;
    }

    private ObjectNode toolCall(String name, Map<String, Object> args, int id) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", "tools/call");
        req.put("id", id);
        ObjectNode params = req.putObject("params");
        params.put("name", name);
        params.set("arguments", mapper.valueToTree(args));
        return req;
    }
}
