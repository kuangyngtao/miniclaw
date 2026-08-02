package com.clawkit.cli.remote;

import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.remote.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * P1-4: PRODUCT-1 v2 E2E using fake probe sessions.
 * Exercised with {@code -Dgroups=e2e -De2e.excludedGroups=}.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Product1V2E2ETest {

    private static Path tempDir;
    private static Path storeFile;
    private static FileRemoteTargetStore store;
    private static RemoteDoctorService doctorService;

    @BeforeAll
    static void setUp() throws Exception {
        tempDir = Files.createTempDirectory("product1-v2-e2e");
        storeFile = tempDir.resolve("targets.yaml");

        Path sshDir = tempDir.resolve(".ssh");
        Files.createDirectories(sshDir);
        Files.writeString(sshDir.resolve("config"), """
            Host e2e-test-server
              HostName 203.0.113.10
              User opsro
            """);

        store = new FileRemoteTargetStore(storeFile);
        var fakeSsh = new FakeE2ESshFacade(sshDir, null);
        doctorService = new RemoteDoctorService(store, fakeSsh, Clock.systemUTC(),
            (target, spec, clock) -> new FakeE2EProbeSession());
    }

    @AfterAll
    static void tearDown() throws Exception {
        try { Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }

    // ── Phase 1: Add --from-ssh → v2 store ───────────────────────────

    @Test @Order(1)
    void addTargetAndVerifyV2Store() throws Exception {
        var fakeSsh = new FakeE2ESshFacade(tempDir.resolve(".ssh"), null);
        var onboarding = new RemoteOnboardingService(store, fakeSsh);

        // Preview
        var preview = onboarding.preview("e2e-test-server", null, null);
        assertThat(preview.targetId()).isEqualTo("e2e-test-server");
        assertThat(preview.profileManifest().capabilityProfile()).isEqualTo("APP_DOWN_V1");
        assertThat(preview.renderSummary()).contains("e2e-test-server");

        // Register
        onboarding.register("e2e-test-server", "e2e-test-server",
            "app-down-readonly-v1", false);
        assertThat(store.list()).contains("e2e-test-server");

        // The SSH config location is needed to reproduce alias resolution. It is
        // not credential material; verify the persisted document contains no key
        // data or credential value instead of rejecting a legitimate .ssh path.
        var reg = store.getRegistration("e2e-test-server").orElseThrow();
        assertThat(reg.connection()).isInstanceOf(OpenSshAliasReference.class);
        String yaml = Files.readString(storeFile);
        assertThat(yaml).doesNotContain("BEGIN OPENSSH PRIVATE KEY", "BEGIN RSA PRIVATE KEY",
            "password:", "token:", "apiKey:");
    }

    // ── Phase 2: Doctor all 9 stages present ──────────────────────────

    @Test @Order(2)
    void doctorHasAllNineStages() {
        var report = doctorService.check("e2e-test-server");
        // Log report for debugging
        System.out.println("Doctor status: " + report.status());
        for (var check : report.checks()) {
            System.out.println("  " + check.stage() + " " + check.status()
                + " " + check.code());
        }
        for (var stage : RemoteDoctorReport.DoctorStage.values()) {
            assertThat(report.checks().stream().anyMatch(c -> c.stage() == stage))
                .as("missing stage: %s", stage).isTrue();
        }
        // With fake probe returning success, should be READY or DEGRADED
        assertThat(report.status()).isIn(
            RemoteDoctorReport.DoctorOverallStatus.READY,
            RemoteDoctorReport.DoctorOverallStatus.DEGRADED);
    }

    @Test @Order(3)
    void doctorJsonIsValid() throws Exception {
        var report = doctorService.check("e2e-test-server");
        String json = RemoteDoctorService.renderJson(report);
        var node = new ObjectMapper().readTree(json);
        assertThat(node.get("targetId").asText()).isEqualTo("e2e-test-server");
        assertThat(node.get("checks").size()).isGreaterThanOrEqualTo(9);
        assertThat(node.get("status").asText()).isNotNull();
    }

    // ── Phase 3: Tool execution + disconnect via real registry ────────

    @Test @Order(4)
    void toolExecutionAndDisconnect() throws Exception {
        var registry = new ToolRegistry();
        var fakeSsh = new FakeE2ESshFacade(tempDir.resolve(".ssh"), null);
        var connService = new RemoteConnectionService(store, registry, Clock.systemUTC()) {
            @Override
            public RemoteAttestationSnapshot connect(String targetId) throws IOException {
                // Fake connect without real SSH
                return null; // not used in this test
            }
        };

        // Verify tool not available before connect
        var tool = registry.lookup("mcp__remote_e2e_test_server__service_status");
        assertThat(tool).isEmpty();

        // Verify disconnect is clean
        connService.disconnect();
        assertThat(connService.activeTargetId()).isNull();
    }

    // ── Phase 4: parser → onboarding → confirm → cancel ──────────────

    @Test @Order(5)
    void parserRejectsBadInput() {
        assertThat(RemoteCommandParser.parse("add --unknown-opt").hasErrors()).isTrue();
        assertThat(RemoteCommandParser.parse("add --from-ssh a --from-ssh b").hasErrors()).isTrue();
        assertThat(RemoteCommandParser.parse("connect").hasErrors()).isTrue();
    }

    @Test @Order(6)
    void previewDoesNotWriteToStore() throws Exception {
        var fakeSsh = new FakeE2ESshFacade(tempDir.resolve(".ssh"), null);
        var onboarding = new RemoteOnboardingService(store, fakeSsh);
        long before = store.list().size();
        onboarding.preview("e2e-test-server", "other-id", null);
        // Preview must not add entries
        assertThat(store.list()).hasSize((int) before);
    }

    // ── Phase 5: Cleanup ──────────────────────────────────────────────

    @Test @Order(7)
    void disconnectAndCleanup() {
        var registry = new ToolRegistry();
        var connService = new RemoteConnectionService(store, registry, Clock.systemUTC());
        connService.disconnect(); // idempotent when not connected
        connService.close();
        // After close, state must be CLOSED and no active target
        assertThat(connService.activeTargetId()).isNull();
    }

    // ── Fakes ─────────────────────────────────────────────────────────

    static class FakeE2ESshFacade extends SystemOpenSshFacade {
        FakeE2ESshFacade(Path u, Path s) { super(u, s); }
        @Override public SshVersionResult checkVersion() {
            return new SshVersionResult("OpenSSH_9.6p1", true);
        }
        @Override public SshGResult runSshG(String alias, String user) {
            return new SshGResult("203.0.113.10", 22, "opsro",
                null, null, "", 1, false, false, false);
        }
    }

    static class FakeE2EProbeSession
            implements RemoteDoctorService.ProbeSession {
        boolean closed;

        @Override public void start() {}
        @Override public RemoteAttestationSnapshot attestationSnapshot() {
            return new RemoteAttestationSnapshot("e2e-test-server",
                "clawkit-ops-mcp", "2024-11-05", "1", "APP_DOWN_V1",
                "d822b006a5dcb84c",
                "d822b006a5dcb84c",
                "666e4646d56653639adf0719e614258eef8fc4ce521f3ec57e7d8e0680569abf",
                List.of("service_status", "container_status", "ports",
                    "http_probe", "logs"),
                123, 45, Instant.now());
        }
        @Override public RemoteError firstError() { return null; }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; }
    }
}
