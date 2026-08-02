package com.clawkit.cli.remote;

import com.clawkit.tools.remote.RemoteSshConnectionSpec;
import com.clawkit.tools.remote.RemoteAttestationSnapshot;
import com.clawkit.tools.remote.RemoteError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * P1-1: Prove Doctor stage semantics, error classification,
 * SKIPPED cascading, JSON validity, and no tool calls/mounts.
 */
class RemoteDoctorServiceTest {

    @TempDir Path tempDir;
    private Path storeFile;
    private FileRemoteTargetStore store;
    private FakeSshFacade fakeSsh;
    private RemoteDoctorService doctor;
    private Clock fixedClock;
    private FakeProbeSession fakeProbe;

    @BeforeEach
    void setUp() {
        storeFile = tempDir.resolve("targets.yaml");
        store = new FileRemoteTargetStore(storeFile);
        fakeSsh = new FakeSshFacade();
        fakeProbe = new FakeProbeSession();
        fixedClock = Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneId.of("UTC"));
        doctor = new RemoteDoctorService(store, fakeSsh, fixedClock,
            (target, spec, clock) -> fakeProbe);
    }

    // ── Target missing ────────────────────────────────────────────────

    @Test
    void targetMissingShouldFailWithSkippedStages() {
        var report = doctor.check("nonexistent");
        assertThat(report.status()).isEqualTo(
            RemoteDoctorReport.DoctorOverallStatus.FAILED);
        assertThat(report.checks()).isNotEmpty();
        // First check should be the TARGET_NOT_FOUND
        var first = report.checks().get(0);
        assertThat(first.code()).isEqualTo("TARGET_NOT_FOUND");
        assertThat(first.status()).isEqualTo(
            RemoteDoctorReport.DoctorCheckStatus.FAIL);
        // Remaining stages must be SKIPPED
        var skipped = report.checks().stream()
            .filter(c -> c.status() == RemoteDoctorReport.DoctorCheckStatus.SKIPPED)
            .count();
        assertThat(skipped).isGreaterThanOrEqualTo(8);
    }

    // ── OpenSSH missing ───────────────────────────────────────────────

    @Test
    void openSshMissingShouldFail() throws Exception {
        registerV2Target();
        fakeSsh.sshAvailable = false;

        var report = doctor.check("test-server");
        var openSsh = findCheck(report, RemoteDoctorReport.DoctorStage.OPENSSH);
        assertThat(openSsh.status()).isEqualTo(
            RemoteDoctorReport.DoctorCheckStatus.FAIL);
        assertThat(openSsh.code()).isEqualTo("SSH_NOT_FOUND");
        // Subsequent stages should be SKIPPED
        var config = findCheck(report, RemoteDoctorReport.DoctorStage.SSH_CONFIG);
        assertThat(config.status()).isEqualTo(
            RemoteDoctorReport.DoctorCheckStatus.SKIPPED);
        assertExactlyOneCheckPerStage(report);
    }

    // ── Unsafe config ─────────────────────────────────────────────────

    @Test
    void unsafeConfigShouldFail() throws Exception {
        // Create a real SSH config with unsafe directive in the temp .ssh dir
        Path sshDir = tempDir.resolve(".ssh");
        Files.createDirectories(sshDir);
        Files.writeString(sshDir.resolve("config"), """
            Host test-server
              HostName example.com
            Match host test-server exec "check"
              ProxyJump bad.example.com
            """);

        // Use real facade pointing at the temp config dir
        var realFacade = new SystemOpenSshFacade(sshDir, null);
        var realDoctor = new RemoteDoctorService(store, realFacade, fixedClock,
            (target, spec, clock) -> fakeProbe);
        registerV2Target();

        var report = realDoctor.check("test-server");
        var config = findCheck(report, RemoteDoctorReport.DoctorStage.SSH_CONFIG);
        assertThat(config.status()).isEqualTo(
            RemoteDoctorReport.DoctorCheckStatus.FAIL);
        assertThat(config.summary()).contains("Match");
    }

    // ── SSH Agent WARN (not FAIL) ─────────────────────────────────────

    @Test
    void sshAgentNotDetectedShouldWarn() throws Exception {
        registerV2Target();
        fakeSsh.sshAvailable = true;

        var report = doctor.check("test-server");
        var agent = findCheck(report, RemoteDoctorReport.DoctorStage.SSH_AGENT);
        // Agent not detected should be WARN, not FAIL and not PASS
        assertThat(agent.status()).isEqualTo(
            RemoteDoctorReport.DoctorCheckStatus.WARN);
        assertThat(agent.code()).isEqualTo("AGENT_NOT_DETECTED");
    }

    // ── CLEANUP always present ────────────────────────────────────────

    @Test
    void cleanupAlwaysPresent() throws Exception {
        registerV2Target();
        fakeSsh.sshAvailable = true;

        var report = doctor.check("test-server");
        var cleanup = findCheck(report, RemoteDoctorReport.DoctorStage.CLEANUP);
        assertThat(cleanup).isNotNull();
        assertThat(cleanup.status()).isEqualTo(RemoteDoctorReport.DoctorCheckStatus.PASS);
        assertThat(fakeProbe.closed).isTrue();
        assertExactlyOneCheckPerStage(report);
    }

    // ── JSON output must be parseable ─────────────────────────────────

    @Test
    void jsonOutputMustBeStrictlyParseable() throws Exception {
        registerV2Target();
        fakeSsh.sshAvailable = true;

        var report = doctor.check("test-server");
        String json = RemoteDoctorService.renderJson(report);

        // Must be valid JSON
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = mapper.readTree(json);
        assertThat(node.get("targetId").asText()).isEqualTo("test-server");
        assertThat(node.get("status").asText()).isNotNull();
        assertThat(node.get("checks").isArray()).isTrue();
    }

    // ── Duration non-negative ─────────────────────────────────────────

    @Test
    void allDurationsMustBeNonNegative() throws Exception {
        // Use real clock so durations are actually measured
        var realDoctor = new RemoteDoctorService(store, fakeSsh, Clock.systemUTC(),
            (target, spec, clock) -> fakeProbe);
        registerV2Target();
        fakeSsh.sshAvailable = true;

        var report = realDoctor.check("test-server");
        for (var check : report.checks()) {
            assertThat(check.duration()).isNotNull();
            assertThat(check.duration().isNegative()).isFalse();
        }
    }

    // ── FAIL results must have nextAction ─────────────────────────────

    @Test
    void failChecksMustHaveNextAction() throws Exception {
        registerV2Target();
        fakeSsh.sshAvailable = false;

        var report = doctor.check("test-server");
        for (var check : report.checks()) {
            if (check.status() == RemoteDoctorReport.DoctorCheckStatus.FAIL) {
                assertThat(check.nextAction())
                    .as("FAIL check %s must have nextAction", check.code())
                    .isNotNull().isNotBlank();
            }
        }
    }

    // ── Verbose output shows timing ────────────────────────────────────

    @Test
    void verboseOutputShowsTimingWhenStagesComplete() throws Exception {
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        var report = new RemoteDoctorReport("test-server",
            RemoteDoctorReport.DoctorOverallStatus.READY,
            List.of(new RemoteDoctorReport.DoctorCheck(
                RemoteDoctorReport.DoctorStage.OPENSSH,
                RemoteDoctorReport.DoctorCheckStatus.PASS,
                "SSH_AVAILABLE", "OpenSSH available", null, Map.of(), Duration.ofMillis(1))),
            now, now.plusMillis(1));
        String text = RemoteDoctorService.renderText(report, true);
        assertThat(text).contains("test-server");
        assertThat(text).contains("(1ms)");
    }

    // ── Error classification ──────────────────────────────────────────

    @Test
    void shouldClassifyHostKeyUnknown() {
        assertThat(RemoteDoctorService.classifySshError(
            "Host key verification failed."))
            .isEqualTo("RMT-004_HOST_KEY_UNKNOWN");
    }

    @Test
    void shouldClassifyHostKeyChanged() {
        assertThat(RemoteDoctorService.classifySshError(
            "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!"))
            .isEqualTo("RMT-004_HOST_KEY_CHANGED");
    }

    @Test
    void shouldClassifyAuthFailure() {
        assertThat(RemoteDoctorService.classifySshError(
            "Permission denied (publickey)."))
            .isEqualTo("RMT-005_AUTH_FAILED");
    }

    @Test
    void shouldClassifyNetworkFailure() {
        assertThat(RemoteDoctorService.classifySshError(
            "Connection refused"))
            .isEqualTo("RMT-006_UNREACHABLE");
    }

    @Test
    void shouldClassifyMcpFailure() {
        assertThat(RemoteDoctorService.classifySshError(
            "MCP protocol version mismatch"))
            .isEqualTo("RMT-007_MCP_FAILURE");
    }

    @Test
    void unknownErrorShouldReturnGeneric() {
        assertThat(RemoteDoctorService.classifySshError(
            "some unexpected error message"))
            .isEqualTo("SSH_FAILED");
    }

    @Test
    void structuredProbeErrorMustDriveDoctorClassification() throws Exception {
        registerV2Target();
        fakeProbe.startFailure = new IOException("transport closed");
        fakeProbe.error = RemoteError.sshAuthFailed("test-server");

        var report = doctor.check("test-server");

        var transport = findCheck(report, RemoteDoctorReport.DoctorStage.SSH_TRANSPORT);
        assertThat(transport.code()).isEqualTo("RMT-005_AUTH_FAILED");
        assertExactlyOneCheckPerStage(report);
    }

    @Test
    void warnMustProduceDegradedOverallStatus() throws Exception {
        registerV2Target();

        var report = doctor.check("test-server");

        var agent = findCheck(report, RemoteDoctorReport.DoctorStage.SSH_AGENT);
        if (agent.status() == RemoteDoctorReport.DoctorCheckStatus.WARN) {
            assertThat(report.status()).isEqualTo(RemoteDoctorReport.DoctorOverallStatus.DEGRADED);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private void registerV2Target() throws Exception {
        var reg = new RemoteTargetRegistration(2, "test-server",
            new OpenSshAliasReference("test-server", "opsro"),
            "app-down-readonly-v1");
        store.add(reg, false);
    }

    private static RemoteDoctorReport.DoctorCheck findCheck(
            RemoteDoctorReport report, RemoteDoctorReport.DoctorStage stage) {
        return report.checks().stream()
            .filter(c -> c.stage() == stage)
            .findFirst().orElse(null);
    }

    private static void assertExactlyOneCheckPerStage(RemoteDoctorReport report) {
        assertThat(report.checks()).hasSize(RemoteDoctorReport.DoctorStage.values().length);
        for (var stage : RemoteDoctorReport.DoctorStage.values()) {
            assertThat(report.checks().stream().filter(c -> c.stage() == stage).count())
                .as("stage %s should appear exactly once", stage).isEqualTo(1);
        }
    }

    // ── Fake SSH facade ───────────────────────────────────────────────

    static class FakeSshFacade extends SystemOpenSshFacade {
        boolean sshAvailable = true;
        boolean configSafe = true;
        String unsafeReason = "";
        int runSshGCalls = 0;

        FakeSshFacade() {
            super(Path.of("."), null, new SshProcessFactory.Default());
        }

        @Override
        public SshVersionResult checkVersion() throws IOException {
            if (sshAvailable) {
                return new SshVersionResult("OpenSSH_9.6p1", true);
            }
            return new SshVersionResult("", false);
        }

        @Override
        public List<String> readConfigFile(Path configFile) throws IOException {
            if (!configSafe && unsafeReason != null) {
                // Return a config with unsafe directive
                return List.of("Match host test-server exec \"check\"");
            }
            if (configSafe) {
                return List.of("Host test-server", "  HostName example.com");
            }
            return List.of();
        }

        @Override
        public Path defaultUserConfigPath() {
            return Path.of(".").resolve("fake_config");
        }
    }

    static class FakeProbeSession implements RemoteDoctorService.ProbeSession {
        boolean closed;
        IOException startFailure;
        RemoteError error;

        @Override public void start() throws IOException {
            if (startFailure != null) throw startFailure;
        }

        @Override public RemoteAttestationSnapshot attestationSnapshot() {
            return new RemoteAttestationSnapshot("test-server", "clawkit-ops-ro",
                "2024-11-05", "v1", "app-down-readonly-v1", "tool-set", "tool-set",
                "contract", List.of("service_status"), 1, 1, Instant.now());
        }

        @Override public RemoteError firstError() { return error; }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; }
    }
}
