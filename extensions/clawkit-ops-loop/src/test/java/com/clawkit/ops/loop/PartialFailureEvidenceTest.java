package com.clawkit.ops.loop;

import com.clawkit.ops.mcp.OpsCapabilityProfile;
import com.clawkit.ops.mcp.OpsMcpServer;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.mcp.McpClient;
import com.clawkit.tools.mcp.McpTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-M3 tests for partial failure during evidence collection.
 *
 * <p>All 7 previously-@Disabled tests now use the
 * {@link RemoteDiscoveryCoordinator} with a fake transport that
 * simulates tool successes, failures, and transport disconnects.
 * No @Disabled tests remain.
 */
class PartialFailureEvidenceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-07-26T00:00:00Z"), java.time.ZoneOffset.UTC);
    private static final String HASH =
        OpsMcpServer.computeToolSetHash(OpsCapabilityProfile.APP_DOWN_V1);

    @TempDir Path tempDir;
    private final List<FakeTransport> transports = new ArrayList<>();

    @AfterEach
    void cleanup() {
        transports.forEach(t -> { try { t.close(); } catch (Exception ignored) {} });
        transports.clear();
    }

    // ── Existing evidence contract tests ──

    @Test void evidenceCollectionStatusDefaultsToObservedWhenSuccessful() {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", true);
        Evidence e = new Evidence("e-1", "inc-1", EvidenceType.CONTAINER_STATUS,
            "mcp:ops/container_status", CLOCK.instant(), CLOCK.instant(),
            "container/g", Evidence.Kind.FACT, fact, "run://r1/tool/tc1",
            Evidence.Freshness.CURRENT, Evidence.Redaction.NONE);
        assertThat(e.collectionStatus()).isEqualTo(Evidence.CollectionStatus.OBSERVED);
    }

    @Test void evidenceBundleRequiresAllEvidenceBelongToSameIncident() {
        ObjectNode f = MAPPER.createObjectNode(); f.put("success", true);
        Evidence e1 = new Evidence("e-1", "inc-1", EvidenceType.SERVICE_STATUS,
            "s", CLOCK.instant(), CLOCK.instant(), "s", Evidence.Kind.FACT, f,
            "r", Evidence.Freshness.CURRENT, Evidence.Redaction.NONE);
        Evidence e2 = new Evidence("e-2", "inc-2", EvidenceType.SERVICE_STATUS,
            "s", CLOCK.instant(), CLOCK.instant(), "s", Evidence.Kind.FACT, f,
            "r", Evidence.Freshness.CURRENT, Evidence.Redaction.NONE);
        assertThatThrownBy(() -> new EvidenceBundle("inc-1", "r1", CLOCK.instant(), List.of(e1, e2)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("belong to the incident");
    }

    @Test void evidenceBundleIsImmutable() {
        ObjectNode f = MAPPER.createObjectNode(); f.put("success", true);
        Evidence e = new Evidence("e-1", "inc-1", EvidenceType.SERVICE_STATUS,
            "s", CLOCK.instant(), CLOCK.instant(), "s", Evidence.Kind.FACT, f,
            "r", Evidence.Freshness.CURRENT, Evidence.Redaction.NONE);
        EvidenceBundle b = new EvidenceBundle("inc-1", "r1", CLOCK.instant(), List.of(e));
        assertThatThrownBy(() -> b.evidence().add(e)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void evidenceRejectsCollectedAtBeforeObservedAt() {
        ObjectNode f = MAPPER.createObjectNode(); f.put("success", true);
        Instant o = CLOCK.instant();
        assertThatThrownBy(() -> new Evidence("e-1", "inc-1", EvidenceType.SERVICE_STATUS,
            "s", o, o.minusSeconds(1), "s", Evidence.Kind.FACT, f, "r",
            Evidence.Freshness.CURRENT, Evidence.Redaction.NONE))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void evidenceIsCurrentAtReturnsFalseAfterValidUntil() {
        ObjectNode f = MAPPER.createObjectNode(); f.put("success", true);
        Instant o = CLOCK.instant();
        Evidence e = new Evidence("e-1", "inc-1", EvidenceType.SERVICE_STATUS,
            "s", o, o, "s", Evidence.Kind.FACT, f, "r", Evidence.Freshness.CURRENT,
            Evidence.Redaction.NONE, "2", Evidence.CollectionStatus.OBSERVED,
            o.plusSeconds(60), null);
        assertThat(e.isCurrentAt(o.plusSeconds(30))).isTrue();
        assertThat(e.isCurrentAt(o.plusSeconds(61))).isFalse();
    }

    // ── PR-M3: Partial failure via RemoteDiscoveryCoordinator (§5.5) ──

    @Test
    void successfulEvidenceMustBePreservedWhenLaterToolFails() throws Exception {
        // 4 specs: spec1 fails, spec2 succeeds, spec3 succeeds, spec4 fails
        var transport = newFakeTransport(
            // initialize
            initializeJson(), appDownToolsJson(),
            // spec1 (service_status gateway) → fail
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":true,"
                + "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"success\\\":false,\\\"error\\\":\\\"COMMAND_FAILED\\\"}\"}]}}",
            // spec2 (service_status demo-api) → success
            toolSuccess(),
            // spec3 (container_status gateway) → success
            toolSuccess(),
            // spec4 (container_status demo-api) → fail
            toolFail("COMMAND_TIMEOUT"));

        var session = newSession(transport);
        var coord = new RemoteDiscoveryCoordinator(session, CLOCK);

        var profile = miniProfile(4, 4); // 4 specs, all required
        DiscoveryResult result = coord.collect("inc-1", "run-1", profile);

        assertThat(result.bundle().evidence()).hasSize(4);
        assertThat(result.status()).isEqualTo(DiscoveryStatus.INCOMPLETE);
        assertThat(result.requiredSuccess()).isEqualTo(2); // only 2 of 4 required succeeded
        // Evidence IDs are predetermined by sequence
        assertThat(result.bundle().evidence().get(0).evidenceId()).isEqualTo("e-1");
        assertThat(result.bundle().evidence().get(1).evidenceId()).isEqualTo("e-2");
        assertThat(result.bundle().evidence().get(2).evidenceId()).isEqualTo("e-3");
        assertThat(result.bundle().evidence().get(3).evidenceId()).isEqualTo("e-4");
    }

    @Test
    void transportDisconnectMustProduceNotCollectedForRemainingEvidence() throws Exception {
        // Provide init+list (2) + 2 tool successes. Transport dies on 3rd tool call.
        var transport = newFakeTransport(
            initializeJson(), appDownToolsJson(),
            toolSuccess(), toolSuccess(), toolSuccess()); // 5 responses
        var session = newSession(transport);
        // Set dieAfterNext AFTER attestation is complete
        transport.dieAfterNextRead();

        var coord = new RemoteDiscoveryCoordinator(session, CLOCK);

        var profile = miniProfile(5, 4);
        DiscoveryResult result = coord.collect("inc-1", "run-1", profile);

        assertThat(result.status()).isEqualTo(DiscoveryStatus.TRANSPORT_FAILED);
        assertThat(result.bundle().evidence()).hasSize(5);
        assertThat(result.bundle().evidence().get(0).collectionStatus())
            .isEqualTo(Evidence.CollectionStatus.COLLECTION_FAILED);
        assertThat(result.bundle().evidence().get(4).collectionStatus())
            .isEqualTo(Evidence.CollectionStatus.COLLECTION_FAILED);
    }

    @Test
    void completenessGateBlocksWhenRequiredEvidenceMissing() throws Exception {
        // All 3 specs fail → 0 required success, minRequired=2 → INCOMPLETE
        var transport = newFakeTransport(
            initializeJson(), appDownToolsJson(),
            toolFail("E1"), toolFail("E2"), toolFail("E3"));
        var session = newSession(transport);
        var coord = new RemoteDiscoveryCoordinator(session, CLOCK);

        var profile = miniProfile(3, 2);
        DiscoveryResult result = coord.collect("inc-1", "run-1", profile);

        assertThat(result.status()).isEqualTo(DiscoveryStatus.INCOMPLETE);
        assertThat(result.requiredSuccess()).isEqualTo(0);
    }

    @Test
    void optionalEvidenceFailureDoesNotBlockDiagnosis() throws Exception {
        // 2 required succeed, 1 optional fails → COMPLETE
        var transport = newFakeTransport(
            initializeJson(), appDownToolsJson(),
            toolSuccess(), toolSuccess(), toolFail("LOG_FAILED"));
        var session = newSession(transport);
        var coord = new RemoteDiscoveryCoordinator(session, CLOCK);

        // 3 specs: 2 required (seq 1,2), 1 optional (seq 3), minRequired=2
        var profile = new DiscoveryProfile("test", 1, List.of(
            EvidenceSpec.required("svc", EvidenceType.SERVICE_STATUS, "s1", "service_status",
                1, Duration.ofSeconds(10), Duration.ofMinutes(2), arguments("service", "gw")),
            EvidenceSpec.required("svc", EvidenceType.SERVICE_STATUS, "s2", "service_status",
                2, Duration.ofSeconds(10), Duration.ofMinutes(2), arguments("service", "api")),
            EvidenceSpec.optional("log", EvidenceType.LOGS, "log", "logs",
                3, Duration.ofSeconds(10), Duration.ofMinutes(2), arguments("service", "gw",
                    "windowSeconds", 300, "tail", 100))
        ), 2);

        DiscoveryResult result = coord.collect("inc-1", "run-1", profile);

        assertThat(result.status()).isEqualTo(DiscoveryStatus.COMPLETE);
        assertThat(result.bundle().evidence()).hasSize(3);
    }

    @Test
    void evidenceValidUntilIsSetFromFreshnessTtl() throws Exception {
        var transport = newFakeTransport(
            initializeJson(), appDownToolsJson(), toolSuccess(), toolSuccess());
        var session = newSession(transport);
        var coord = new RemoteDiscoveryCoordinator(session, CLOCK);

        var profile = miniProfile(2, 1);
        DiscoveryResult result = coord.collect("inc-1", "run-1", profile);

        Evidence e = result.bundle().evidence().get(0);
        assertThat(e.collectionStatus()).isEqualTo(Evidence.CollectionStatus.OBSERVED);
        assertThat(e.validUntil()).isNotNull();
        assertThat(e.validUntil()).isAfter(e.observedAt());
    }

    @Test
    void failedEvidenceHasNullValidUntil() throws Exception {
        var transport = newFakeTransport(
            initializeJson(), appDownToolsJson(), toolFail("FAIL"));
        var session = newSession(transport);
        var coord = new RemoteDiscoveryCoordinator(session, CLOCK);

        var profile = miniProfile(1, 1);
        DiscoveryResult result = coord.collect("inc-1", "run-1", profile);

        Evidence e = result.bundle().evidence().get(0);
        assertThat(e.collectionStatus()).isEqualTo(Evidence.CollectionStatus.COLLECTION_FAILED);
        assertThat(e.validUntil()).isNull();
    }

    // ── Data preservation tests (§1) ──

    @Test
    void successfulEvidencePreservesDataFromRemote() throws Exception {
        String toolData = "{\"success\":true,"
            + "\"data\":{\"service\":\"gateway\",\"State\":\"running\",\"Health\":\"healthy\"},"
            + "\"errorCode\":null,\"error\":null}";
        var transport = newFakeTransport(initializeJson(), appDownToolsJson(),
            mkToolResult(toolData));
        var session = newSession(transport);
        var coord = new RemoteDiscoveryCoordinator(session, CLOCK);
        var profile = miniProfile(1, 1);
        DiscoveryResult result = coord.collect("inc-1", "run-1", profile);

        Evidence e = result.bundle().evidence().get(0);
        assertThat(e.collectionStatus()).isEqualTo(Evidence.CollectionStatus.OBSERVED);
        assertThat(e.fact().path("success").asBoolean()).isTrue();
        assertThat(e.fact().has("data")).isTrue();
        assertThat(e.fact().path("data").path("State").asText()).isEqualTo("running");
    }

    @Test
    void httpFailurePreservesStatusCodeAndUnhealthyFlag() throws Exception {
        String toolData = "{\"success\":true,"
            + "\"data\":{\"endpoint\":\"gateway-health\",\"statusCode\":503,\"healthy\":false},"
            + "\"errorCode\":null,\"error\":null}";
        var transport = newFakeTransport(initializeJson(), appDownToolsJson(),
            mkToolResult(toolData));
        var session = newSession(transport);
        var coord = new RemoteDiscoveryCoordinator(session, CLOCK);
        var profile = miniProfile(1, 1);
        DiscoveryResult result = coord.collect("inc-1", "run-1", profile);

        Evidence e = result.bundle().evidence().get(0);
        assertThat(e.collectionStatus()).isEqualTo(Evidence.CollectionStatus.OBSERVED);
        assertThat(e.fact().path("data").path("statusCode").asInt()).isEqualTo(503);
        assertThat(e.fact().path("data").path("healthy").asBoolean()).isFalse();
    }

    @Test
    void urisAndConnectionDetailsAreRedacted() throws Exception {
        String toolData = "{\"success\":true,"
            + "\"data\":{\"URL\":\"http://secret:pass@host:8080/path\","
            + "\"jdbcUrl\":\"jdbc:postgresql://db:5432/mydb\","
            + "\"State\":\"running\"}}";
        var transport = newFakeTransport(initializeJson(), appDownToolsJson(),
            mkToolResult(toolData));
        var session = newSession(transport);
        var coord = new RemoteDiscoveryCoordinator(session, CLOCK);
        var profile = miniProfile(1, 1);
        DiscoveryResult result = coord.collect("inc-1", "run-1", profile);

        Evidence e = result.bundle().evidence().get(0);
        assertThat(e.fact().toString()).doesNotContain("secret:pass");
        assertThat(e.fact().toString()).doesNotContain("postgresql://");
        assertThat(e.fact().path("data").path("State").asText()).isEqualTo("running");
    }

    @Test
    void failedEvidenceDoesNotGenerateFakeData() throws Exception {
        String toolData = "{\"success\":false,\"errorCode\":\"COMMAND_FAILED\","
            + "\"error\":\"No such container\"}";
        var transport = newFakeTransport(initializeJson(), appDownToolsJson(),
            mkToolResult(toolData));
        var session = newSession(transport);
        var coord = new RemoteDiscoveryCoordinator(session, CLOCK);
        var profile = miniProfile(1, 1);
        DiscoveryResult result = coord.collect("inc-1", "run-1", profile);

        Evidence e = result.bundle().evidence().get(0);
        assertThat(e.collectionStatus()).isEqualTo(Evidence.CollectionStatus.COLLECTION_FAILED);
        assertThat(e.fact().path("success").asBoolean()).isFalse();
        assertThat(e.fact().path("errorCode").asText()).isEqualTo("COMMAND_FAILED");
        assertThat(e.fact().has("data")).isFalse();
    }

    @Test
    void evidenceBundleCannotBeModifiedAfterFreeze() throws Exception {
        var transport = newFakeTransport(
            initializeJson(), appDownToolsJson(), toolSuccess());
        var session = newSession(transport);
        var coord = new RemoteDiscoveryCoordinator(session, CLOCK);

        var profile = miniProfile(1, 1);
        DiscoveryResult result = coord.collect("inc-1", "run-1", profile);

        // Bundle is immutable
        assertThatThrownBy(() -> result.bundle().evidence().add(
            result.bundle().evidence().get(0)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── Helpers ──

    private DiscoveryProfile miniProfile(int count, int minRequired) {
        List<EvidenceSpec> specs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            specs.add(EvidenceSpec.required(
                "svc", EvidenceType.SERVICE_STATUS, "scope" + i,
                "service_status", i + 1,
                Duration.ofSeconds(10), Duration.ofMinutes(2),
                arguments("service", "gw")));
        }
        return new DiscoveryProfile("mini", 1, specs, Math.min(minRequired, count));
    }

    private static java.util.Map<String, Object> arguments(Object... pairs) {
        var m = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    private RemoteOpsSession newSession(McpTransport transport) throws IOException {
        Path idFile = tempDir.resolve("id_test");
        Path khFile = tempDir.resolve("known_hosts");
        Files.createFile(idFile);
        Files.createFile(khFile);
        var target = new RemoteTargetDescriptor("test", "APP_DOWN_V1", "1", HASH,
            "fb8239b41bfbbdb2d4bba7b3fa24d2fecc1f7df975f988f509d1fd6c97993ed2");
        var config = new SshConnectionConfig("h", 22, "u", idFile, khFile,
            Duration.ofSeconds(10), Duration.ofSeconds(10), 32768);
        var client = new McpClient(transport, "test");
        var session = new RemoteOpsSession(target, config, CLOCK, transport, client);
        session.doInitializeAndAttest();
        return session;
    }

    private FakeTransport newFakeTransport(String... responses) {
        var t = new FakeTransport(responses);
        transports.add(t);
        return t;
    }

    private static String initializeJson() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\","
            + "\"serverInfo\":{\"name\":\"clawkit-ops-mcp\",\"version\":\"0.1.0\","
            + "\"probeVersion\":\"1\",\"capabilityProfile\":\"APP_DOWN_V1\","
            + "\"toolSetHash\":\"" + HASH + "\"}}}";
    }

    private static String appDownToolsJson() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":["
            + "{\"name\":\"service_status\",\"description\":\"x\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}},"
            + "\"annotations\":{\"readOnlyHint\":true,\"destructiveHint\":false,"
            + "\"openWorldHint\":false,\"idempotentHint\":true},"
            + "\"outputSchema\":{\"type\":\"object\",\"properties\":{}}},"
            + "{\"name\":\"container_status\",\"description\":\"x\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}},"
            + "\"annotations\":{\"readOnlyHint\":true,\"destructiveHint\":false,"
            + "\"openWorldHint\":false,\"idempotentHint\":true},"
            + "\"outputSchema\":{\"type\":\"object\",\"properties\":{}}},"
            + "{\"name\":\"ports\",\"description\":\"x\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}},"
            + "\"annotations\":{\"readOnlyHint\":true,\"destructiveHint\":false,"
            + "\"openWorldHint\":false,\"idempotentHint\":true},"
            + "\"outputSchema\":{\"type\":\"object\",\"properties\":{}}},"
            + "{\"name\":\"http_probe\",\"description\":\"x\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}},"
            + "\"annotations\":{\"readOnlyHint\":true,\"destructiveHint\":false,"
            + "\"openWorldHint\":false,\"idempotentHint\":true},"
            + "\"outputSchema\":{\"type\":\"object\",\"properties\":{}}},"
            + "{\"name\":\"logs\",\"description\":\"x\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}},"
            + "\"annotations\":{\"readOnlyHint\":true,\"destructiveHint\":false,"
            + "\"openWorldHint\":false,\"idempotentHint\":true},"
            + "\"outputSchema\":{\"type\":\"object\",\"properties\":{}}}"
            + "]}}";
    }

    private static String toolSuccess() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":false,"
            + "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"success\\\":true}\"}]}}";
    }

    private static String mkToolResult(String textContent) {
        // Full JSON-RPC wrapper with text content containing the OpsToolResult JSON
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":false,"
            + "\"content\":[{\"type\":\"text\",\"text\":"
            + escapeJson(textContent) + "}]}}";
    }

    private static String escapeJson(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String toolFail(String error) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":true,"
            + "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"success\\\":false,"
            + "\\\"error\\\":\\\"" + error + "\\\"}\"}]}}";
    }

    // ── Fake transport (simplified from TransportAndProfileSecurityTest) ──

    static class FakeTransport implements McpTransport {
        private final java.util.Queue<String> responses;
        private boolean alive = true, dieAfterNext;

        FakeTransport(String... responses) {
            this.responses = new java.util.ArrayDeque<>();
            for (String r : responses) this.responses.add(r);
        }

        void dieAfterNextRead() { dieAfterNext = true; }

        @Override public void start() {}
        @Override public boolean isAlive() { return alive; }

        @Override public String send(String req) throws IOException {
            return send(req, ExecutionControl.none());
        }

        @Override
        public String send(String req, ExecutionControl ctrl) throws IOException {
            if (dieAfterNext) { alive = false; throw new IOException("transport not alive"); }
            if (!req.contains("\"id\"")) return "{}";
            var r = responses.poll();
            if (r == null) throw new IOException("no more responses");
            return r;
        }

        @Override public void stop() { alive = false; }
        @Override public void close() { stop(); }
    }
}
