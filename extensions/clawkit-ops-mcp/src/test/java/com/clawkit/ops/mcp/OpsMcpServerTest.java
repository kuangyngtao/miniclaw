package com.clawkit.ops.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpsMcpServerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
