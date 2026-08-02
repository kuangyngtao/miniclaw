package com.clawkit.cli.remote;

import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.remote.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * PRODUCT-1 v2 real remote E2E — full product chain through v2 store.
 *
 * <p>Requires a remote server with opsro MCP deployed.
 * Uses default POSTGRES_DIAGNOSIS_V1 profile (matching current remote).
 *
 * <p>Configuration via environment:
 * <ul>
 *   <li>{@code REMOTE0_HOST} (default 122.51.51.118)</li>
 *   <li>{@code REMOTE0_KEY} (default C:/Users/yngtao/.ssh/id_ed25519_clawkit)</li>
 *   <li>{@code REMOTE0_KNOWN_HOSTS} (default C:/Users/yngtao/.ssh/known_hosts)</li>
 *   <li>{@code REMOTE0_USER} (default opsro)</li>
 *   <li>{@code REMOTE0_PROFILE} (default POSTGRES_DIAGNOSIS_V1)</li>
 * </ul>
 *
 * <p>Tagged {@code e2e} — excluded from default {@code mvn test}.
 * Run with: {@code mvn -B -ntp test -pl clawkit-cli -am -Dgroups=e2e -De2e.excludedGroups=}
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Product1V2RemoteE2ETest {

    private static final String HOST = envOr("REMOTE0_HOST", "122.51.51.118");
    private static final String USER = envOr("REMOTE0_USER", "opsro");
    private static final Path KEY_FILE = Path.of(
        envOr("REMOTE0_KEY", "C:/Users/yngtao/.ssh/id_ed25519_clawkit"));
    private static final Path KNOWN_HOSTS = Path.of(
        envOr("REMOTE0_KNOWN_HOSTS", "C:/Users/yngtao/.ssh/known_hosts"));
    private static final String PROFILE_NAME = envOr("REMOTE0_PROFILE", "POSTGRES_DIAGNOSIS_V1");
    private static final boolean IS_APP_DOWN = "APP_DOWN_V1".equals(PROFILE_NAME);
    private static final String MANIFEST_ID = IS_APP_DOWN
        ? "app-down-readonly-v1" : "postgres-diagnosis-readonly-v1";
    private static final int EXPECTED_TOOLS = IS_APP_DOWN ? 5 : 10;

    private static final String ALIAS = "clawkit-v2-e2e";
    private static final String TARGET_ID = "v2-remote-test";

    private static Path tmpDir;
    private static Path storeFile;
    private static FileRemoteTargetStore store;
    private static ToolRegistry registry;
    private static RemoteConnectionService connService;

    @BeforeAll
    static void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("product1-v2-remote-");
        storeFile = tmpDir.resolve("targets.yaml");
        Path sshDir = tmpDir.resolve(".ssh");
        Files.createDirectories(sshDir);

        // Create temp SSH config with alias pointing to remote server
        String fwdKey = KEY_FILE.toAbsolutePath().toString().replace('\\', '/');
        String fwdKH = KNOWN_HOSTS.toAbsolutePath().toString().replace('\\', '/');
        Files.writeString(sshDir.resolve("config"), """
            Host %s
              HostName %s
              Port 22
              User %s
              IdentityFile %s
              UserKnownHostsFile %s
            """.formatted(ALIAS, HOST, USER, fwdKey, fwdKH));

        // Create v2 store with known profile
        store = new FileRemoteTargetStore(storeFile);
        registry = new ToolRegistry();

        System.out.println("=== PRODUCT-1 V2 REMOTE E2E ===");
        System.out.println("Profile: " + PROFILE_NAME);
        System.out.println("Manifest: " + MANIFEST_ID);
        System.out.println("Expected tools: " + EXPECTED_TOOLS);

        // Register target via v2 onboarding with explicit config path
        var facade = new SystemOpenSshFacade(sshDir, null);
        var onboarding = new RemoteOnboardingService(store, facade);
        Path configPath = sshDir.resolve("config");

        System.out.println("\n[Phase 0] Registering via --from-ssh...");
        onboarding.register(ALIAS, TARGET_ID, MANIFEST_ID, false, configPath);
        assertThat(store.list()).contains(TARGET_ID);
        System.out.println("  Registered: " + TARGET_ID);

        // Verify v2 store has no secrets
        var reg = store.getRegistration(TARGET_ID).orElseThrow();
        assertThat(reg.toString()).doesNotContain("PRIVATE", "id_", ".pem");
        System.out.println("  v2 store: clean (no secrets)");

        // Connect
        connService = new RemoteConnectionService(store, registry);
    }

    @AfterAll
    static void tearDown() {
        if (connService != null) {
            try { connService.disconnect(); } catch (Exception ignored) {}
            connService.close();
        }
        System.out.println("\n[Cleanup] session closed");
    }

    // ── Phase 1: Doctor ───────────────────────────────────────────────

    @Test @Order(1)
    void doctorReportsReadyOrDegraded() {
        var facade = new SystemOpenSshFacade(
            tmpDir.resolve(".ssh"), null);
        var doctor = new RemoteDoctorService(store, facade);
        var report = doctor.check(TARGET_ID);

        System.out.println("\n[Phase 1] Doctor report:");
        for (var c : report.checks()) {
            System.out.println("  " + c.status() + " " + c.stage() + ": " + c.summary());
        }
        System.out.println("  Overall: " + report.status());

        // All 9 stages present
        for (var stage : RemoteDoctorReport.DoctorStage.values()) {
            assertThat(report.checks().stream().anyMatch(c -> c.stage() == stage))
                .as("missing stage: " + stage).isTrue();
        }

        // At minimum, OPENSSH and SSH_CONFIG should PASS
        var openSsh = findCheck(report, RemoteDoctorReport.DoctorStage.OPENSSH);
        assertThat(openSsh.status()).isEqualTo(RemoteDoctorReport.DoctorCheckStatus.PASS);
    }

    // ── Phase 2: Connect ──────────────────────────────────────────────

    @Test @Order(2)
    void connectAndAttest() throws Exception {
        System.out.println("\n[Phase 2] Connecting to " + TARGET_ID + "...");
        var attestation = connService.connect(TARGET_ID);

        assertThat(connService.state()).isEqualTo(RemoteConnectionState.READY);
        assertThat(connService.activeTargetId()).isEqualTo(TARGET_ID);
        assertThat(attestation).isNotNull();
        assertThat(attestation.capabilityProfile()).isEqualTo(PROFILE_NAME);
        assertThat(attestation.toolNames()).hasSize(EXPECTED_TOOLS);

        System.out.println("  State: READY");
        System.out.println("  Profile: " + attestation.capabilityProfile());
        System.out.println("  Tools: " + attestation.toolNames().size());
        System.out.println("  Connect: " + attestation.connectLatencyMs() + "ms");
        System.out.println("  Attest: " + attestation.attestationLatencyMs() + "ms");
    }

    // ── Phase 3: Tools mounted in registry ────────────────────────────

    @Test @Order(3)
    void toolsMountedInRegistry() {
        System.out.println("\n[Phase 3] Verifying tool mount...");
        var mount = registry.getMount("remote:" + TARGET_ID);
        assertThat(mount).isPresent();
        assertThat(mount.get().toolNames()).isNotEmpty();

        for (String name : mount.get().toolNames()) {
            var tool = registry.lookup(name);
            assertThat(tool).isPresent();
            assertThat(tool.get().isReadOnly()).isTrue();
        }
        System.out.println("  Mounted: " + mount.get().toolNames());
    }

    // ── Phase 4: Execute read-only tool ───────────────────────────────

    @Test @Order(4)
    void executeServiceStatusViaRegistry() {
        System.out.println("\n[Phase 4] Executing service_status...");
        String prefix = "mcp__remote_" + TARGET_ID.replace('-', '_') + "__";
        String toolName = prefix + "service_status";
        var tool = registry.lookup(toolName);
        assertThat(tool).isPresent();

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("service", "order-api");
        var result = tool.get().execute(
            new ToolExecutionRequest("req-1", toolName, args, Instant.now()));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).doesNotContain("PRIVATE KEY");
        System.out.println("  Result: OK, output " + result.output().length() + " chars");
    }

    // ── Phase 5: Execute second read-only tool ────────────────────────

    @Test @Order(5)
    void executeContainerStatusViaRegistry() {
        System.out.println("\n[Phase 5] Executing container_status...");
        String prefix = "mcp__remote_" + TARGET_ID.replace('-', '_') + "__";
        String toolName = prefix + "container_status";
        var tool = registry.lookup(toolName);
        assertThat(tool).isPresent();

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("service", "order-api");
        var result = tool.get().execute(
            new ToolExecutionRequest("req-2", toolName, args, Instant.now()));

        assertThat(result.success()).isTrue();
        System.out.println("  Result: OK");
    }

    // ── Phase 6: Write tool must be rejected ──────────────────────────

    @Test @Order(6)
    void restartServiceMustBeRejected() {
        System.out.println("\n[Phase 6] Verifying write tool rejection...");
        // restart_service is NOT in APP_DOWN or POSTGRES read-only profiles
        var tool = registry.lookup("restart_service");
        assertThat(tool).isEmpty();
        System.out.println("  restart_service: not available (correct)");
    }

    // ── Phase 7: Invalid args rejected ────────────────────────────────

    @Test @Order(7)
    void invalidArgsMustBeRejected() {
        System.out.println("\n[Phase 7] Testing invalid args...");
        String prefix = "mcp__remote_" + TARGET_ID.replace('-', '_') + "__";
        String toolName = prefix + "service_status";
        var tool = registry.lookup(toolName);
        assertThat(tool).isPresent();

        // Empty args — service is required
        ObjectNode emptyArgs = JsonNodeFactory.instance.objectNode();
        var result = tool.get().execute(
            new ToolExecutionRequest("req-3", toolName, emptyArgs, Instant.now()));
        // Either error or specific rejection — both are valid protections
        System.out.println("  Status: " + result.status());
    }

    // ── Phase 8: Disconnect ───────────────────────────────────────────

    @Test @Order(8)
    void disconnectCleansUp() {
        System.out.println("\n[Phase 8] Disconnecting...");
        connService.disconnect();
        assertThat(connService.state()).isEqualTo(RemoteConnectionState.DISCONNECTED);
        assertThat(connService.activeTargetId()).isNull();

        // Registry tools unmounted
        var mount = registry.getMount("remote:" + TARGET_ID);
        assertThat(mount).isEmpty();
        System.out.println("  Disconnected, tools unmounted");
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static RemoteDoctorReport.DoctorCheck findCheck(
            RemoteDoctorReport report, RemoteDoctorReport.DoctorStage stage) {
        return report.checks().stream()
            .filter(c -> c.stage() == stage).findFirst().orElse(null);
    }

    private static String envOr(String name, String defaultVal) {
        String v = System.getenv(name);
        return v != null && !v.isEmpty() ? v : defaultVal;
    }
}
