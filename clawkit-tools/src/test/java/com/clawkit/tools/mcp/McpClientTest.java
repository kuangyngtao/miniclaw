package com.clawkit.tools.mcp;

import com.clawkit.tools.control.ExecutionControl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for McpClient JSON-RPC error paths.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>tools/call JSON-RPC error → McpCallResult.error (not IOException)</li>
 *   <li>initialize JSON-RPC error → IOException with structured message</li>
 *   <li>tools/list JSON-RPC error → IOException (not silent empty list)</li>
 *   <li>Malformed response without result or error → IOException</li>
 * </ul>
 */
class McpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── tools/call JSON-RPC error ──

    @Test void callToolJsonRpcErrorReturnsMcpCallResultError() throws Exception {
        var transport = new FixedResponseTransport(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,"
                + "\"message\":\"service is not allowlisted: postgres\"}}");
        var client = new McpClient(transport, "test");

        McpCallResult result = client.callTool("container_status",
            MAPPER.createObjectNode().put("service", "postgres"));

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("-32602");
        assertThat(result.text()).contains("not allowlisted");
        assertThat(transport.receivedRequest).isTrue();
    }

    @Test void callToolJsonRpcErrorDoesNotThrowIOException() throws Exception {
        var transport = new FixedResponseTransport(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,"
                + "\"message\":\"Method not found\"}}");
        var client = new McpClient(transport, "test");

        // Must NOT throw — callTool returns McpCallResult.error for JSON-RPC errors
        McpCallResult result = client.callTool("nonexistent",
            MAPPER.createObjectNode());
        assertThat(result.isError()).isTrue();
    }

    // ── initialize JSON-RPC error ──

    @Test void initializeJsonRpcErrorThrowsIOException() {
        var transport = new FixedResponseTransport(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,"
                + "\"message\":\"Server unavailable\"}}");
        var client = new McpClient(transport, "test");

        assertThatThrownBy(() -> client.initialize())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("[MCP:test]")
            .hasMessageContaining("-32000")
            .hasMessageContaining("Server unavailable");
    }

    @Test void initializeJsonRpcErrorThrowsStructuredException() {
        var transport = new FixedResponseTransport(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603,"
                + "\"message\":\"Internal error\",\"data\":\"trace-abc\"}}");
        var client = new McpClient(transport, "test");

        assertThatThrownBy(() -> client.initialize())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("-32603")
            .hasMessageContaining("Internal error");
    }

    // ── tools/list JSON-RPC error ──

    @Test void listToolsJsonRpcErrorThrowsIOException() {
        var transport = new FixedResponseTransport(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,"
                + "\"message\":\"Server not ready\"}}");
        var client = new McpClient(transport, "test");

        // Must throw — listTools must not silently return empty list on JSON-RPC error
        assertThatThrownBy(() -> client.listTools())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("[MCP:test]")
            .hasMessageContaining("-32000")
            .hasMessageContaining("Server not ready");
    }

    // ── Malformed response ──

    @Test void callToolMalformedResponseThrowsIOException() {
        // No "result" and no "error" — protocol violation
        var transport = new FixedResponseTransport(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"unknownField\":42}");
        var client = new McpClient(transport, "test");

        assertThatThrownBy(() -> client.callTool("test_tool",
            MAPPER.createObjectNode()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("malformed response")
            .hasMessageContaining("no result, no error");
    }

    @Test void initializeMalformedResponseThrowsIOException() {
        var transport = new FixedResponseTransport(
            "{\"jsonrpc\":\"2.0\",\"id\":1}");
        var client = new McpClient(transport, "test");

        assertThatThrownBy(() -> client.initialize())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("initialize failed");
    }

    @Test void transportSendThrowsIOExceptionForConnectivityFailure() {
        var transport = new McpTransport() {
            @Override public void start() {}
            @Override public boolean isAlive() { return false; }
            @Override public String send(String request, ExecutionControl ctrl) throws IOException {
                throw new IOException("connection refused");
            }
            @Override public String send(String request) throws IOException {
                throw new IOException("connection refused");
            }
            @Override public void stop() {}
            @Override public void close() {}
        };
        var client = new McpClient(transport, "test");

        // Transport-level IOException must propagate (not silently swallowed)
        assertThatThrownBy(() -> client.callTool("test_tool",
            MAPPER.createObjectNode()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("connection refused");
    }

    // ── Normal response (regression) ──

    @Test void callToolNormalSuccessReturnsMcpCallResult() throws Exception {
        var transport = new FixedResponseTransport(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":false,"
                + "\"content\":[{\"type\":\"text\","
                + "\"text\":\"{\\\"success\\\":true,\\\"data\\\":{\\\"rows\\\":5}}\"}]}}");
        var client = new McpClient(transport, "test");

        McpCallResult result = client.callTool("db_activity",
            MAPPER.createObjectNode());

        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("\"success\":true");
        assertThat(result.text()).contains("\"rows\":5");
    }

    // ── Fake transport ──

    static class FixedResponseTransport implements McpTransport {
        private final String response;
        boolean receivedRequest;

        FixedResponseTransport(String response) {
            this.response = response;
        }

        @Override public void start() {}
        @Override public boolean isAlive() { return true; }
        @Override public String send(String request) {
            receivedRequest = true;
            return response;
        }
        @Override public String send(String request, ExecutionControl ctrl) {
            receivedRequest = true;
            return response;
        }
        @Override public void stop() {}
        @Override public void close() {}
    }
}
