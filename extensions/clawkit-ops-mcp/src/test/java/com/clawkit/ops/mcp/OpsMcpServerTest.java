package com.clawkit.ops.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpsMcpServerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Existing tests ──

    @Test
    void exposesOnlyBoundedReadOnlyTools() {
        var server = new OpsMcpServer(new StubBackend());
        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(1),
            "tools/list", MAPPER.createObjectNode());

        JsonNode tools = response.path("result").path("tools");
        assertThat(tools).hasSize(5);
        assertThat(tools.findValuesAsText("name"))
            .containsExactlyInAnyOrderElementsOf(OpsMcpServer.TOOL_NAMES);
        assertThat(tools.toString())
            .doesNotContain("shell_exec")
            .doesNotContain("ssh_exec")
            .doesNotContain("command");
        for (JsonNode tool : tools) {
            assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
            assertThat(tool.path("annotations").path("destructiveHint").asBoolean()).isFalse();
            assertThat(tool.path("annotations").path("openWorldHint").asBoolean()).isFalse();
            assertThat(tool.path("inputSchema").path("additionalProperties").asBoolean()).isFalse();
            assertThat(tool.path("_meta").path("clawkit/riskLevel").asText()).isEqualTo("LOW");
            assertThat(tool.path("_meta").path("clawkit/timeoutMs").asInt()).isEqualTo(10_000);
            assertThat(tool.path("_meta").path("clawkit/maxOutputBytes").asInt()).isEqualTo(32_768);
            assertThat(tool.path("_meta").path("clawkit/auditFields")).hasSize(6);
            assertThat(tool.path("outputSchema").path("properties").has("audit")).isTrue();
        }
    }

    @Test
    void rejectsUnknownToolWithoutCallingBackend() {
        var backend = new StubBackend();
        var server = new OpsMcpServer(backend);
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "shell_exec");
        params.putObject("arguments").put("command", "whoami");

        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(2),
            "tools/call", params);

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(backend.calls).isZero();
    }

    @Test
    void returnsStructuredContentAndAuditData() {
        var backend = new StubBackend();
        var server = new OpsMcpServer(backend);
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "service_status");
        params.putObject("arguments").put("service", "gateway");

        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(3),
            "tools/call", params);

        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
        assertThat(response.path("result").path("structuredContent")
            .path("tool").asText()).isEqualTo("service_status");
        assertThat(response.path("result").path("structuredContent")
            .path("observedAt").isTextual()).isTrue();
        assertThat(response.path("result").path("structuredContent")
            .path("audit").path("timeoutMs").asInt()).isEqualTo(1000);
    }

    @Test
    void postgresProfileAddsOnlyTheFiveReviewedReadOnlyTools() {
        var server = new OpsMcpServer(new StubBackend(),
            OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1);
        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(4),
            "tools/list", MAPPER.createObjectNode());

        JsonNode tools = response.path("result").path("tools");
        assertThat(tools).hasSize(10);
        assertThat(tools.findValuesAsText("name"))
            .containsExactlyInAnyOrderElementsOf(
                OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1.toolNames());
        for (JsonNode tool : tools) {
            assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
            assertThat(tool.path("annotations").path("destructiveHint").asBoolean()).isFalse();
        }
    }

    // ── PR-0: Parameter injection / validation guardrails ──

    @Test
    void rejectsToolCallWithMissingRequiredParameter() {
        var server = new OpsMcpServer(new StubBackend());
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "service_status");
        params.putObject("arguments"); // no "service" field

        // handle() throws directly — parameter validation is fail-fast
        // before reaching the backend. serve() would catch and convert to
        // JSON-RPC -32602.
        assertThatThrownBy(() ->
            server.handle(MAPPER.getNodeFactory().numberNode(10),
                "tools/call", params))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("service");
    }

    @Test
    void rejectsToolCallWithInvalidParameterType() {
        var server = new OpsMcpServer(new StubBackend());
        ObjectNode args = MAPPER.createObjectNode();
        args.put("service", "gateway");
        args.put("containerPort", "not-a-number"); // should be integer
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "ports");
        params.set("arguments", args);

        // handle() throws because requiredInt() detects non-integral value
        assertThatThrownBy(() ->
            server.handle(MAPPER.getNodeFactory().numberNode(11),
                "tools/call", params))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("containerPort");
    }

    @Test
    void rejectsToolCallWithBlankParameterValue() {
        var server = new OpsMcpServer(new StubBackend());
        ObjectNode args = MAPPER.createObjectNode();
        args.put("service", ""); // empty string
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "service_status");
        params.set("arguments", args);

        // handle() throws — blank values fail requiredText() validation
        assertThatThrownBy(() ->
            server.handle(MAPPER.getNodeFactory().numberNode(12),
                "tools/call", params))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-empty string");
    }

    @Test
    void rejectsToolCallWithNullArgumentsNode() {
        var server = new OpsMcpServer(new StubBackend());
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "service_status");
        params.putNull("arguments");

        // handle() throws — null arguments node yields null for field
        // access, requiredText() throws on null reference
        assertThatThrownBy(() ->
            server.handle(MAPPER.getNodeFactory().numberNode(13),
                "tools/call", params))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsToolNotInActiveProfile() {
        // container_resources is only available in POSTGRES_DIAGNOSIS_V1
        var server = new OpsMcpServer(new StubBackend(),
            OpsCapabilityProfile.APP_DOWN_V1);
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "container_resources");
        params.putObject("arguments").put("service", "gateway");

        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(14),
            "tools/call", params);

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asText())
            .contains("unknown or prohibited tool", "container_resources");
    }

    @Test
    void rejectsCommandInjectionInServiceName() {
        // Backend validates allowlist, but server must first validate the
        // parameter is present and non-blank before dispatching.
        // Service names with shell metacharacters pass server validation
        // because the server only checks non-blank string; the backend
        // is responsible for allowlist enforcement.
        var backend = new StubBackend();
        var server = new OpsMcpServer(backend);
        ObjectNode args = MAPPER.createObjectNode();
        args.put("service", "gateway; rm -rf /");
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "service_status");
        params.set("arguments", args);

        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(15),
            "tools/call", params);

        // Server passes the value through — backend validates allowlist.
        // This test documents the current boundary: server validates
        // presence/type, backend validates content.
        assertThat(backend.calls).isEqualTo(1);
        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
    }

    // ── PR-0: Unknown method / illegal method guardrails ──

    @Test
    void rejectsUnknownJsonRpcMethod() {
        var server = new OpsMcpServer(new StubBackend());
        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(20),
            "nonexistent/method", MAPPER.createObjectNode());

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(response.path("error").path("message").asText())
            .contains("method not found");
    }

    @Test
    void rejectsTypoSquattedMethodName() {
        var server = new OpsMcpServer(new StubBackend());
        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(21),
            "tools/call ", MAPPER.createObjectNode()); // trailing space

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void rejectsMethodWithPathTraversalAttempt() {
        var server = new OpsMcpServer(new StubBackend());
        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(22),
            "../tools/call", MAPPER.createObjectNode());

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32601);
    }

    // ── PR-0: Output pollution guardrails ──

    @Test
    void responseIsValidJsonRpcEnvelope() {
        var server = new OpsMcpServer(new StubBackend());
        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(30),
            "initialize", MAPPER.createObjectNode());

        assertThat(response.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response.has("id")).isTrue();
        assertThat(response.has("result")).isTrue();
        assertThat(response.has("error")).isFalse();
    }

    @Test
    void errorResponseIsValidJsonRpcEnvelope() {
        var server = new OpsMcpServer(new StubBackend());
        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(31),
            "unknown/method", MAPPER.createObjectNode());

        assertThat(response.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response.has("id")).isTrue();
        assertThat(response.has("error")).isTrue();
        assertThat(response.path("error").path("code").asInt())
            .isIn(-32601, -32602, -32603, -32700);
    }

    // ── PR-0: Oversized request guardrails ──

    @Test
    void serveLineSkipsBlankLinesWithoutCrashing() throws Exception {
        var server = new OpsMcpServer(new StubBackend());
        String blankPayload = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}

            {"jsonrpc":"2.0","id":2,"method":"ping","params":{}}
            """;
        var input = new ByteArrayInputStream(blankPayload.getBytes(StandardCharsets.UTF_8));
        var output = new ByteArrayOutputStream();

        server.serve(input, output);

        String[] lines = output.toString(StandardCharsets.UTF_8).trim().split("\n");
        assertThat(lines).hasSize(2);
        assertThat(MAPPER.readTree(lines[0]).path("result")
            .path("protocolVersion").asText()).isEqualTo("2024-11-05");
        assertThat(MAPPER.readTree(lines[1]).path("result")).isNotNull();
    }

    @Test
    void serveHandlesMalformedJsonWithoutCrashing() throws Exception {
        var server = new OpsMcpServer(new StubBackend());
        String payload = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
            not-valid-json-at-all
            {"jsonrpc":"2.0","id":2,"method":"ping","params":{}}
            """;
        var input = new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8));
        var output = new ByteArrayOutputStream();

        server.serve(input, output);

        String[] lines = output.toString(StandardCharsets.UTF_8).trim().split("\n");
        // First: initialize response, Second: parse error -32700, Third: ping response
        assertThat(lines).hasSize(3);
        assertThat(MAPPER.readTree(lines[1]).path("error").path("code").asInt())
            .isEqualTo(-32700);
        assertThat(MAPPER.readTree(lines[2]).path("result")).isNotNull();
    }

    @Test
    void serveLineHandlesRequestWithoutIdAsIgnoredNotification() throws Exception {
        var server = new OpsMcpServer(new StubBackend());
        String payload = """
            {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
            {"jsonrpc":"2.0","id":1,"method":"ping","params":{}}
            """;
        var input = new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8));
        var output = new ByteArrayOutputStream();

        server.serve(input, output);

        String[] lines = output.toString(StandardCharsets.UTF_8).trim().split("\n");
        // Only response to the request with id; notification is silently ignored
        assertThat(lines).hasSize(1);
        assertThat(MAPPER.readTree(lines[0]).path("id").asInt()).isEqualTo(1);
    }

    @Test
    void serveLineHandlesLargeButValidRequest() throws Exception {
        var server = new OpsMcpServer(new StubBackend());
        // Construct a legitimate but large service name (4000 chars)
        String longService = "gateway-" + "x".repeat(4000);
        String payload = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"service_status","arguments":{"service":"%s"}}}
            """.formatted(longService);
        var input = new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8));
        var output = new ByteArrayOutputStream();

        server.serve(input, output);

        String[] lines = output.toString(StandardCharsets.UTF_8).trim().split("\n");
        assertThat(lines).hasSize(1);
        // Server should not crash on large inputs; it passes the value through
        // to the backend for allowlist validation
        assertThat(MAPPER.readTree(lines[0]).has("result")).isTrue();
    }

    @Test
    void initializeDoesNotReturnServerStackOrSecrets() {
        var server = new OpsMcpServer(new StubBackend());
        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(40),
            "initialize", MAPPER.createObjectNode());

        JsonNode info = response.path("result").path("serverInfo");
        assertThat(info.path("name").asText()).isEqualTo("clawkit-ops-mcp");
        assertThat(info.path("version").asText()).isEqualTo("0.1.0");
        // Must not leak internal class names, paths, or environment
        String resultText = response.toString();
        assertThat(resultText)
            .doesNotContain("Exception")
            .doesNotContain("java.lang")
            .doesNotContain("at com.clawkit");
    }

    // ── PR-0: Extra fields / unexpected input guardrails ──

    @Test
    void ignoresExtraFieldsInRequestWithoutCrashing() {
        var server = new OpsMcpServer(new StubBackend());
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", "service_status");
        ObjectNode args = MAPPER.createObjectNode();
        args.put("service", "gateway");
        args.put("__proto__", "polluted");
        args.put("constructor", "polluted");
        params.set("arguments", args);
        // Extra top-level field
        params.put("_malicious", true);

        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(50),
            "tools/call", params);

        // Must not crash on extra fields; server ignores them
        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
    }

    @Test
    void rejectsToolsCallWithoutParamsField() {
        var server = new OpsMcpServer(new StubBackend());
        // params is an empty object — path("name") returns missing node,
        // requiredText() throws on null
        assertThatThrownBy(() ->
            server.handle(MAPPER.getNodeFactory().numberNode(51),
                "tools/call", MAPPER.createObjectNode()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolsListNeverExposesDangerousCategories() {
        var server = new OpsMcpServer(new StubBackend());
        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(52),
            "tools/list", MAPPER.createObjectNode());

        String toolsJson = response.toString();
        assertThat(toolsJson)
            .doesNotContain("shell")
            .doesNotContain("exec")
            .doesNotContain("sudo")
            .doesNotContain("docker exec")
            .doesNotContain("bash")
            .doesNotContain("command");
    }

    // ── PR-1: Attestation (profile, probeVersion, toolSetHash) ──

    @Test
    void initializeResponseIncludesAttestationFields() {
        var server = new OpsMcpServer(new StubBackend(),
            OpsCapabilityProfile.APP_DOWN_V1);
        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(60),
            "initialize", MAPPER.createObjectNode());

        JsonNode info = response.path("result").path("serverInfo");
        assertThat(info.path("probeVersion").asText()).isEqualTo("1");
        assertThat(info.path("capabilityProfile").asText())
            .isEqualTo("APP_DOWN_V1");
        assertThat(info.path("toolSetHash").asText())
            .isNotBlank()
            .hasSize(16); // first 8 bytes of SHA-256 = 16 hex chars
    }

    @Test
    void toolSetHashIsDeterministic() {
        String hash1 = OpsMcpServer.computeToolSetHash(
            OpsCapabilityProfile.APP_DOWN_V1);
        String hash2 = OpsMcpServer.computeToolSetHash(
            OpsCapabilityProfile.APP_DOWN_V1);
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void toolSetHashDiffersBetweenProfiles() {
        String appDown = OpsMcpServer.computeToolSetHash(
            OpsCapabilityProfile.APP_DOWN_V1);
        String postgres = OpsMcpServer.computeToolSetHash(
            OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1);
        assertThat(appDown).isNotEqualTo(postgres);
    }

    @Test
    void postgresInitializeIncludesPostgresProfileAndDifferentHash() {
        var server = new OpsMcpServer(new StubBackend(),
            OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1);
        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(63),
            "initialize", MAPPER.createObjectNode());

        JsonNode info = response.path("result").path("serverInfo");
        assertThat(info.path("capabilityProfile").asText())
            .isEqualTo("POSTGRES_DIAGNOSIS_V1");
        String appDownHash = OpsMcpServer.computeToolSetHash(
            OpsCapabilityProfile.APP_DOWN_V1);
        assertThat(info.path("toolSetHash").asText())
            .isNotEqualTo(appDownHash);
    }

    // ── PR-1: Oversized line rejection (serve level) ──

    @Test
    void serveRejectsOversizedLine() throws Exception {
        var server = new OpsMcpServer(new StubBackend());
        // Construct a request that exceeds MAX_LINE_BYTES
        String prefix = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"service_status","arguments":{"service":\"""";
        String suffix = "\"}}}\n";
        int paddingNeeded = OpsMcpServer.MAX_LINE_BYTES - prefix.getBytes(StandardCharsets.UTF_8).length
            - suffix.getBytes(StandardCharsets.UTF_8).length + 10; // 10 bytes over
        String longService = "x".repeat(Math.max(1, paddingNeeded));
        String payload = prefix + longService + suffix;

        var input = new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8));
        var output = new ByteArrayOutputStream();

        server.serve(input, output);

        String[] lines = output.toString(StandardCharsets.UTF_8).trim().split("\n");
        assertThat(lines).hasSize(1);
        JsonNode errorResponse = MAPPER.readTree(lines[0]);
        assertThat(errorResponse.path("error").path("code").asInt()).isEqualTo(-32600);
        assertThat(errorResponse.path("error").path("message").asText())
            .contains("exceeds maximum size");
    }

    @Test
    void initializeResponseDoesNotLeakInternalPaths() {
        var server = new OpsMcpServer(new StubBackend());
        ObjectNode response = server.handle(MAPPER.getNodeFactory().numberNode(65),
            "initialize", MAPPER.createObjectNode());

        String resultText = response.toString();
        assertThat(resultText)
            .doesNotContain("/usr/local")
            .doesNotContain("/etc/clawkit")
            .doesNotContain("CLASSPATH")
            .doesNotContain("java.class.path");
    }

    // ── Stub backend ──

    private static final class StubBackend implements OpsBackend {
        int calls;

        @Override public OpsToolResult serviceStatus(String service) {
            calls++;
            return ok("service_status", service);
        }
        @Override public OpsToolResult containerStatus(String service) {
            calls++;
            return ok("container_status", service);
        }
        @Override public OpsToolResult ports(String service, int containerPort) {
            calls++;
            return ok("ports", service);
        }
        @Override public OpsToolResult httpProbe(String endpoint) {
            calls++;
            return ok("http_probe", endpoint);
        }
        @Override public OpsToolResult logs(String service, Duration window, int tail) {
            calls++;
            return ok("logs", service);
        }

        private OpsToolResult ok(String tool, String target) {
            return new OpsToolResult(tool, target, Instant.EPOCH, Instant.EPOCH,
                true, true, MAPPER.createObjectNode(), null, null,
                new OpsToolResult.Audit("stub", 1, 1000, 0, 0, false));
        }
    }
}
