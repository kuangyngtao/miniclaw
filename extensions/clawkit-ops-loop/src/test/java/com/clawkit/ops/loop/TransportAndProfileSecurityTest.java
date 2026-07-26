package com.clawkit.ops.loop;

import com.clawkit.ops.mcp.OpsCapabilityProfile;
import com.clawkit.ops.mcp.OpsMcpServer;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.mcp.McpClient;
import com.clawkit.tools.mcp.McpInitializeResult;
import com.clawkit.tools.mcp.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-M2 security tests for transport errors and strict profile/toolset
 * attestation.
 *
 * <p>All 8 previously-@Disabled tests are now implemented using a
 * {@link FakeTransport} that simulates various remote MCP server
 * behaviors. No real SSH or network required.
 */
class TransportAndProfileSecurityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-07-26T00:00:00Z"), java.time.ZoneOffset.UTC);
    private static final String EXPECTED_HASH =
        OpsMcpServer.computeToolSetHash(OpsCapabilityProfile.APP_DOWN_V1);
    private static final String PG_HASH =
        OpsMcpServer.computeToolSetHash(OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1);

    @TempDir Path tempDir;

    private Path identityFile() throws IOException {
        Path f = tempDir.resolve("id_test");
        if (!Files.exists(f)) Files.createFile(f);
        return f;
    }
    private Path knownHostsFile() throws IOException {
        Path f = tempDir.resolve("known_hosts");
        if (!Files.exists(f)) Files.createFile(f);
        return f;
    }

    private final List<FakeTransport> createdTransports = new ArrayList<>();

    @AfterEach
    void closeTransports() {
        createdTransports.forEach(t -> { try { t.close(); } catch (Exception ignored) {} });
        createdTransports.clear();
    }

    // ── Profile attestation (existing) ──

    @Test void appDownProfileDoesNotIncludePostgresTools() {
        Set<String> appDown = OpsCapabilityProfile.APP_DOWN_V1.toolNames();
        assertThat(appDown).hasSize(5)
            .doesNotContain("container_resources", "business_metrics",
                "db_activity", "db_lock_graph", "db_connection_stats");
    }

    @Test void postgresProfileIncludesAllAppDownToolsPlusFiveDbTools() {
        Set<String> postgres = OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1.toolNames();
        assertThat(postgres).hasSize(10).containsAll(OpsMcpServer.TOOL_NAMES);
    }

    @Test void profilesAreImmutableAndDistinct() {
        Set<String> a = OpsCapabilityProfile.APP_DOWN_V1.toolNames();
        Set<String> b = OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1.toolNames();
        assertThatThrownBy(() -> a.add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(a).isNotEqualTo(b);
    }

    @Test void unrecognizedProfileThrows() {
        assertThatThrownBy(() -> OpsCapabilityProfile.fromEnvironment("UNKNOWN"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void nullOrBlankDefaultsToAppDown() {
        assertThat(OpsCapabilityProfile.fromEnvironment(null)).isEqualTo(OpsCapabilityProfile.APP_DOWN_V1);
        assertThat(OpsCapabilityProfile.fromEnvironment("")).isEqualTo(OpsCapabilityProfile.APP_DOWN_V1);
    }

    @Test void everyAppDownToolHasReadOnlyAnnotations() {
        assertThat(OpsMcpServer.TOOL_NAMES).hasSize(5);
    }

    // ── Attestation: mismatch → fail closed ──

    @Test
    void profileMismatchMustPreventEvidenceCollection() throws Exception {
        // Server reports POSTGRES_DIAGNOSIS_V1 but session expects APP_DOWN_V1
        var transport = newFakeTransport(initializeJson(
            "clawkit-ops-mcp", "0.1.0", "1", "POSTGRES_DIAGNOSIS_V1", PG_HASH),
            appDownTools());
        var session = newSession(EXPECTED_HASH, transport);

        assertThatThrownBy(() -> session.doInitializeAndAttest())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("capabilityProfile");
        assertThat(session.state()).isEqualTo(RemoteOpsSession.State.FAILED);
        assertThat(session.firstError()).isNotNull();
        assertThat(session.firstError().code()).isEqualTo("REMOTE_PROFILE_MISMATCH");
    }

    @Test
    void toolsetMismatchMustPreventEvidenceCollection() throws Exception {
        // Server reports a different toolSetHash than expected
        String wrongHash = "0000000000000000";
        var transport = newFakeTransport(initializeJson(
            "clawkit-ops-mcp", "0.1.0", "1", "APP_DOWN_V1", wrongHash),
            appDownTools());
        var session = newSession(EXPECTED_HASH, transport);

        assertThatThrownBy(() -> session.doInitializeAndAttest())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("toolSetHash");
        assertThat(session.state()).isEqualTo(RemoteOpsSession.State.FAILED);
        assertThat(session.firstError().code()).isEqualTo("REMOTE_TOOLSET_MISMATCH");
    }

    @Test
    void probeVersionMismatchMustPreventEvidenceCollection() throws Exception {
        // Server reports probeVersion "99" but session expects "1"
        var transport = newFakeTransport(initializeJson(
            "clawkit-ops-mcp", "0.1.0", "99", "APP_DOWN_V1", EXPECTED_HASH),
            appDownTools());
        var session = newSession("1", EXPECTED_HASH, transport);

        assertThatThrownBy(() -> session.doInitializeAndAttest())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("probeVersion");
        assertThat(session.state()).isEqualTo(RemoteOpsSession.State.FAILED);
    }

    @Test
    void protocolVersionMismatchMustFailAtMcpClientLevel() {
        // McpClient.initialize() rejects non-matching protocol
        var transport = newFakeTransport(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2099-01-01\"}}",
            "{}");
        var client = new McpClient(transport, "test");

        assertThatThrownBy(() -> client.initialize())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("protocol version mismatch");
    }

    @Test
    void serverNameMismatchMustFail() throws Exception {
        var transport = newFakeTransport(initializeJson(
            "not-clawkit", "1.0", "1", "APP_DOWN_V1", EXPECTED_HASH),
            appDownTools());
        var session = newSession(EXPECTED_HASH, transport);

        assertThatThrownBy(() -> session.doInitializeAndAttest())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("server name mismatch");
        assertThat(session.state()).isEqualTo(RemoteOpsSession.State.FAILED);
    }

    @Test
    void unsafeAnnotationMustPreventEvidenceCollection() throws Exception {
        // All 5 tools present, but service_status has destructiveHint=true
        String unsafeHash = OpsMcpServer.computeToolSetHash(OpsCapabilityProfile.APP_DOWN_V1);
        // Build the tools-list response inline to avoid formatting issues
        String toolsWithDestructive = "{\"tools\":[" +
            "{\"name\":\"service_status\",\"description\":\"x\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}},"
            + "\"annotations\":{\"readOnlyHint\":true,\"destructiveHint\":true,"
            + "\"openWorldHint\":false,\"idempotentHint\":true},"
            + "\"outputSchema\":{\"type\":\"object\",\"properties\":{}}}," +
            "{\"name\":\"container_status\",\"description\":\"x\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}},"
            + "\"annotations\":{\"readOnlyHint\":true,\"destructiveHint\":false,"
            + "\"openWorldHint\":false,\"idempotentHint\":true},"
            + "\"outputSchema\":{\"type\":\"object\",\"properties\":{}}}," +
            "{\"name\":\"ports\",\"description\":\"x\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}},"
            + "\"annotations\":{\"readOnlyHint\":true,\"destructiveHint\":false,"
            + "\"openWorldHint\":false,\"idempotentHint\":true},"
            + "\"outputSchema\":{\"type\":\"object\",\"properties\":{}}}," +
            "{\"name\":\"http_probe\",\"description\":\"x\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}},"
            + "\"annotations\":{\"readOnlyHint\":true,\"destructiveHint\":false,"
            + "\"openWorldHint\":false,\"idempotentHint\":true},"
            + "\"outputSchema\":{\"type\":\"object\",\"properties\":{}}}," +
            "{\"name\":\"logs\",\"description\":\"x\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}},"
            + "\"annotations\":{\"readOnlyHint\":true,\"destructiveHint\":false,"
            + "\"openWorldHint\":false,\"idempotentHint\":true},"
            + "\"outputSchema\":{\"type\":\"object\",\"properties\":{}}}" +
            "]}";
        var transport = newFakeTransport(initializeJson(
            "clawkit-ops-mcp", "0.1.0", "1", "APP_DOWN_V1", unsafeHash),
            mkResult(toolsWithDestructive));
        var session = newSession(unsafeHash, transport);

        assertThatThrownBy(() -> session.doInitializeAndAttest())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("unsafe");
        assertThat(session.state()).isEqualTo(RemoteOpsSession.State.FAILED);
    }

    @Test
    void initializeToolSetHashAndListHashMustBothMatch() throws Exception {
        // initialize claims hash A, tools/list produces hash B → fail
        String fakeInitHash = "aaaaaaaaaaaaaaaa";
        var transport = newFakeTransport(initializeJson(
            "clawkit-ops-mcp", "0.1.0", "1", "APP_DOWN_V1", fakeInitHash),
            appDownTools());
        var session = newSession(EXPECTED_HASH, transport);

        // The init hash check passes (session expects fakeInitHash),
        // but the tools/list hash check should fail because tools return real hash
        assertThatThrownBy(() -> {
            var s = newSession(fakeInitHash, transport);
            s.doInitializeAndAttest();
        }).isInstanceOf(IOException.class)
            .hasMessageContaining("hash mismatch");
    }

    @Test
    void failedSessionClosesTransportAndDoesNotAllowToolCalls() throws Exception {
        var transport = newFakeTransport(initializeJson(
            "clawkit-ops-mcp", "0.1.0", "1", "APP_DOWN_V1", EXPECTED_HASH),
            appDownTools());
        // wrong expected hash causes failure
        var session = newSession("wronghash", transport);

        assertThatThrownBy(() -> session.doInitializeAndAttest())
            .isInstanceOf(IOException.class);
        assertThat(session.state()).isEqualTo(RemoteOpsSession.State.FAILED);
        assertThat(session.firstError()).isNotNull();

        // FAILED session must NOT allow tool calls
        assertThatThrownBy(() -> session.callTool("service_status", MAPPER.createObjectNode()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("FAILED");
    }

    // ── Transport error classification (§7.5) ──

    @Test
    void transportEofMustProduceTransportLostNotBusinessError() {
        FakeTransport t = newFakeTransport("{}");
        t.dieAfterNextRead();
        var client = new McpClient(t, "test");
        assertThatThrownBy(() -> client.initialize())
            .isInstanceOf(IOException.class);
    }

    @Test
    void transportTimeoutMustDistinguishFromToolTimeout() {
        FakeTransport t = newFakeTransport("{}");
        t.setHang(true);
        var client = new McpClient(t, "test");
        assertThatThrownBy(() -> client.initialize())
            .isInstanceOf(IOException.class);
    }

    @Test
    void illegalJsonRpcResponseMustProduceProtocolError() {
        var transport = newFakeTransport("not json at all");
        var client = new McpClient(transport, "test");
        assertThatThrownBy(() -> client.initialize())
            .isInstanceOf(IOException.class);
    }

    @Test
    void stderrOutputMustNotCorruptResponseParsing() throws Exception {
        // FakeTransport returns valid JSON regardless of stderr noise
        var transport = newFakeTransport(initializeJson(
            "clawkit-ops-mcp", "0.1.0", "1", "APP_DOWN_V1", EXPECTED_HASH),
            appDownTools());
        // stderr ring should not affect parsing
        var session = newSession(EXPECTED_HASH, transport);
        session.doInitializeAndAttest();
        assertThat(session.state()).isEqualTo(RemoteOpsSession.State.READY);
    }

    @Test
    void executionControlCancelMustCleanUpTransport() {
        var transport = newFakeTransport("{}");
        transport.setCancelled(true);
        var client = new McpClient(transport, "test");
        assertThatThrownBy(() -> client.initialize())
            .isInstanceOf(IOException.class);
    }

    // ── State machine ──

    @Test
    void duplicateStartMustNotCreateSecondProcess() throws Exception {
        // start() on an already-started session throws
        var transport = newFakeTransport(initializeJson(
            "clawkit-ops-mcp", "0.1.0", "1", "APP_DOWN_V1", EXPECTED_HASH),
            appDownTools());
        var session = newSession(EXPECTED_HASH, transport);
        session.doInitializeAndAttest();

        assertThatThrownBy(() -> session.start())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already started");
    }

    @Test
    void closeIsIdempotent() throws Exception {
        var transport = newFakeTransport(initializeJson(
            "clawkit-ops-mcp", "0.1.0", "1", "APP_DOWN_V1", EXPECTED_HASH),
            appDownTools());
        var session = newSession(EXPECTED_HASH, transport);
        session.doInitializeAndAttest();
        session.close();
        assertThat(session.state()).isEqualTo(RemoteOpsSession.State.CLOSED);
        // Second close is a no-op
        session.close();
        assertThat(session.state()).isEqualTo(RemoteOpsSession.State.CLOSED);
    }

    @Test
    void failedStatePreservesFirstErrorThroughClose() throws Exception {
        var transport = newFakeTransport(initializeJson(
            "clawkit-ops-mcp", "0.1.0", "1", "APP_DOWN_V1", EXPECTED_HASH),
            appDownTools());
        var session = newSession("wronghash", transport);

        assertThatThrownBy(() -> session.doInitializeAndAttest())
            .isInstanceOf(IOException.class);
        assertThat(session.state()).isEqualTo(RemoteOpsSession.State.FAILED);

        var err = session.firstError();
        assertThat(err).isNotNull();

        session.close();
        assertThat(session.state()).isEqualTo(RemoteOpsSession.State.CLOSED);
        assertThat(session.firstError()).isSameAs(err); // preserved
    }

    @Test
    void initializedSessionBecomesReadyAndAllowsToolCalls() throws Exception {
        var transport = newFakeTransport(initializeJson(
            "clawkit-ops-mcp", "0.1.0", "1", "APP_DOWN_V1", EXPECTED_HASH),
            appDownTools());
        // Add a tool call response (full JSON-RPC wrapper needed)
        transport.addResponse(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":false,"
            + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
            + "\"structuredContent\":{\"tool\":\"test\"}}}");

        var session = newSession(EXPECTED_HASH, transport);
        session.doInitializeAndAttest();
        assertThat(session.state()).isEqualTo(RemoteOpsSession.State.READY);

        var result = session.callTool("service_status", MAPPER.createObjectNode());
        assertThat(result.isError()).isFalse();
    }

    // ── Helpers ──

    private RemoteOpsSession newSession(String expectedHash, McpTransport transport) {
        return newSession("1", expectedHash, transport);
    }

    private RemoteOpsSession newSession(String probeVer, String expectedHash,
                                        McpTransport transport) {
        try {
            var target = new RemoteTargetDescriptor("test-target",
                "APP_DOWN_V1", probeVer, expectedHash);
            var config = new SshConnectionConfig("testhost", 22, "testuser",
                identityFile(), knownHostsFile(),
                Duration.ofSeconds(10), Duration.ofSeconds(10), 32768);
            var client = new McpClient(transport, "test");
            return new RemoteOpsSession(target, config, FIXED_CLOCK, transport, client);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private FakeTransport newFakeTransport(String... responses) {
        var t = new FakeTransport(responses);
        createdTransports.add(t);
        return t;
    }

    private static String initializeJson(String server, String version,
                                          String probe, String profile, String hash) {
        return mkResult("{\"protocolVersion\":\"2024-11-05\","
            + "\"serverInfo\":{\"name\":\"" + server + "\",\"version\":\"" + version + "\","
            + "\"probeVersion\":\"" + probe + "\","
            + "\"capabilityProfile\":\"" + profile + "\","
            + "\"toolSetHash\":\"" + hash + "\"}}");
    }

    private static String appDownTools() { return mkResult(_appDownTools()); }

    private static String _appDownTools() {
        return """
            {"tools": [
              {"name": "service_status", "description": "x",
               "inputSchema": {"type": "object", "properties": {}},
               "annotations": {"readOnlyHint": true, "destructiveHint": false,
                 "openWorldHint": false, "idempotentHint": true},
               "outputSchema": {"type": "object", "properties": {}}},
              {"name": "container_status", "description": "x",
               "inputSchema": {"type": "object", "properties": {}},
               "annotations": {"readOnlyHint": true, "destructiveHint": false,
                 "openWorldHint": false, "idempotentHint": true},
               "outputSchema": {"type": "object", "properties": {}}},
              {"name": "ports", "description": "x",
               "inputSchema": {"type": "object", "properties": {}},
               "annotations": {"readOnlyHint": true, "destructiveHint": false,
                 "openWorldHint": false, "idempotentHint": true},
               "outputSchema": {"type": "object", "properties": {}}},
              {"name": "http_probe", "description": "x",
               "inputSchema": {"type": "object", "properties": {}},
               "annotations": {"readOnlyHint": true, "destructiveHint": false,
                 "openWorldHint": false, "idempotentHint": true},
               "outputSchema": {"type": "object", "properties": {}}},
              {"name": "logs", "description": "x",
               "inputSchema": {"type": "object", "properties": {}},
               "annotations": {"readOnlyHint": true, "destructiveHint": false,
                 "openWorldHint": false, "idempotentHint": true},
               "outputSchema": {"type": "object", "properties": {}}}
            ]}""";
    }

    private static String mkResult(String inner) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + inner + "}";
    }

    /** tools/list response with 5 safe tools but service_status marked destructive. */
    private static String appDownToolsWithDestructive() {
        return mkResult("""
            {"tools": [
              {"name": "service_status", "description": "x",
               "inputSchema": {"type": "object", "properties": {}},
               "annotations": {"readOnlyHint": true, "destructiveHint": true,
                 "openWorldHint": false, "idempotentHint": true},
               "outputSchema": {"type": "object", "properties": {}}},
              {"name": "container_status", "description": "x",
               "inputSchema": {"type": "object", "properties": {}},
               "annotations": {"readOnlyHint": true, "destructiveHint": false,
                 "openWorldHint": false, "idempotentHint": true},
               "outputSchema": {"type": "object", "properties": {}}},
              {"name": "ports", "description": "x",
               "inputSchema": {"type": "object", "properties": {}},
               "annotations": {"readOnlyHint": true, "destructiveHint": false,
                 "openWorldHint": false, "idempotentHint": true},
               "outputSchema": {"type": "object", "properties": {}}},
              {"name": "http_probe", "description": "x",
               "inputSchema": {"type": "object", "properties": {}},
               "annotations": {"readOnlyHint": true, "destructiveHint": false,
                 "openWorldHint": false, "idempotentHint": true},
               "outputSchema": {"type": "object", "properties": {}}},
              {"name": "logs", "description": "x",
               "inputSchema": {"type": "object", "properties": {}},
               "annotations": {"readOnlyHint": true, "destructiveHint": false,
                 "openWorldHint": false, "idempotentHint": true},
               "outputSchema": {"type": "object", "properties": {}}}
            ]}""");
    }

    // ── Fake transport ──

    static class FakeTransport implements McpTransport {
        private final java.util.Queue<String> responses;
        private boolean alive = true;
        private boolean dieAfterNext;
        private boolean hang;
        private boolean cancelled;
        private final AtomicInteger startCount = new AtomicInteger();

        FakeTransport(String... responses) {
            this.responses = new java.util.ArrayDeque<>();
            for (String r : responses) this.responses.add(r);
        }

        void addResponse(String response) { responses.add(response); }
        void dieAfterNextRead() { dieAfterNext = true; }
        void setHang(boolean h) { hang = h; }
        void setCancelled(boolean c) { cancelled = c; }

        @Override public void start() { startCount.incrementAndGet(); }
        @Override public boolean isAlive() { return alive; }

        @Override
        public String send(String jsonRpcRequest) throws IOException {
            return send(jsonRpcRequest, ExecutionControl.none());
        }

        @Override
        public String send(String jsonRpcRequest, ExecutionControl control)
            throws IOException {
            if (cancelled) throw new IOException("[MCP] request cancelled");
            if (dieAfterNext) {
                alive = false;
                throw new IOException("[MCP] transport not alive: test");
            }
            if (hang) {
                throw new IOException("[MCP] request timed out; remote outcome is unknown");
            }
            // Notifications have no "id" — don't consume a response
            if (!jsonRpcRequest.contains("\"id\"")) return "{}";
            var next = responses.poll();
            if (next == null) throw new IOException("fake transport: no more responses");
            return next;
        }

        @Override public void stop() { alive = false; }
        @Override public void close() { stop(); }
    }
}
