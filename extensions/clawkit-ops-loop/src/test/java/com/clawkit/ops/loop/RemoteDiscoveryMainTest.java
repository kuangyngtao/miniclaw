package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteDiscoveryMainTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("ops-m2-0-test-");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var s = Files.walk(tempDir)) {
                s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }

    // ── Config / CLI error paths ──

    @Test void missingTargetReturns4() {
        assertThat(RemoteDiscoveryMain.run(new String[]{"--profile", "APP_DOWN_V1"}))
            .isEqualTo(4);
    }

    @Test void unknownArgReturns4() {
        assertThat(RemoteDiscoveryMain.run(new String[]{"--unknown", "x"}))
            .isEqualTo(4);
    }

    @Test void missingRequiredEnvReturns4() {
        assertThat(RemoteDiscoveryMain.run(new String[]{"--target", "test"}))
            .isEqualTo(4);
    }

    @Test void invalidProfileReturns4() {
        try {
            System.setProperty("CLAWKIT_REMOTE_OPS_EXPECTED_PROFILE", "INVALID");
            assertThat(RemoteDiscoveryMain.run(new String[]{"--target", "test"}))
                .isEqualTo(4);
        } finally {
            System.clearProperty("CLAWKIT_REMOTE_OPS_EXPECTED_PROFILE");
        }
    }

    @Test void noArgsShowsUsageReturns4() {
        assertThat(RemoteDiscoveryMain.run(new String[]{}))
            .isEqualTo(4);
    }

    @Test void invalidPortReturns4() {
        try {
            System.setProperty("CLAWKIT_REMOTE_OPS_HOST", "example.com");
            System.setProperty("CLAWKIT_REMOTE_OPS_USER", "testuser");
            System.setProperty("CLAWKIT_REMOTE_OPS_IDENTITY_FILE", "/nonexistent/key");
            System.setProperty("CLAWKIT_REMOTE_OPS_EXPECTED_PROFILE", "APP_DOWN_V1");
            System.setProperty("CLAWKIT_REMOTE_OPS_PORT", "notanumber");
            assertThat(RemoteDiscoveryMain.run(new String[]{"--target", "test"}))
                .isEqualTo(4);
        } finally {
            clearOpsEnv();
        }
    }

    @Test void missingIdentityFileReturns4() {
        try {
            System.setProperty("CLAWKIT_REMOTE_OPS_HOST", "example.com");
            System.setProperty("CLAWKIT_REMOTE_OPS_USER", "testuser");
            System.setProperty("CLAWKIT_REMOTE_OPS_IDENTITY_FILE",
                tempDir.resolve("nonexistent_key").toString());
            System.setProperty("CLAWKIT_REMOTE_OPS_EXPECTED_PROFILE", "APP_DOWN_V1");
            assertThat(RemoteDiscoveryMain.run(new String[]{"--target", "test"}))
                .isEqualTo(4);
        } finally {
            clearOpsEnv();
        }
    }

    @Test void unknownProfileNameReturns4() throws Exception {
        try {
            // Create a dummy key file so we pass the file-exists check
            Path keyFile = tempDir.resolve("test_key");
            Files.writeString(keyFile, "dummy-key-content");

            System.setProperty("CLAWKIT_REMOTE_OPS_HOST", "example.com");
            System.setProperty("CLAWKIT_REMOTE_OPS_USER", "testuser");
            System.setProperty("CLAWKIT_REMOTE_OPS_IDENTITY_FILE", keyFile.toString());
            System.setProperty("CLAWKIT_REMOTE_OPS_EXPECTED_PROFILE", "UNKNOWN_PROFILE_XYZ");
            assertThat(RemoteDiscoveryMain.run(new String[]{"--target", "test"}))
                .isEqualTo(4);
        } finally {
            clearOpsEnv();
        }
    }

    // ── Exit code for empty args with explicit output dir ──

    @Test void validArgsButMissingHostEnvReturns4() {
        assertThat(RemoteDiscoveryMain.run(new String[]{
            "--target", "test", "--profile", "APP_DOWN_V1", "--output", tempDir.toString()
        })).isEqualTo(4);
    }

    // ── RemoteIncidentResult contract ──

    private static EvidenceBundle minimalBundle(String incidentId, String runId) {
        ObjectNode fact = new ObjectMapper().createObjectNode();
        fact.put("success", true);
        Evidence e = new Evidence("e-1", incidentId, EvidenceType.SERVICE_STATUS,
            "mcp:ops/svc", Instant.now(), Instant.now(), "scope",
            Evidence.Kind.FACT, fact, "run://r1/e-1",
            Evidence.Freshness.CURRENT, Evidence.Redaction.NONE,
            "2", Evidence.CollectionStatus.OBSERVED,
            Instant.now().plusSeconds(120), null);
        return new EvidenceBundle(incidentId, runId, Instant.now(), List.of(e));
    }

    @Test void remoteIncidentResultRejectsNullDiscovery() {
        try {
            new RemoteIncidentResult(null,
                new Diagnosis("X", 0.5, List.of(), List.of(),
                    List.of(), List.of(), "ESCALATE", false),
                false, null, Instant.now());
            assertThat(true).as("expected NPE").isFalse();
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("discovery");
        }
    }

    @Test void remoteIncidentResultRejectsNullDiagnosis() {
        try {
            new RemoteIncidentResult(
                new DiscoveryResult("inc-1", "run-1", "P1",
                    minimalBundle("inc-1", "run-1"),
                    DiscoveryStatus.INCOMPLETE, 0, 6, Instant.now()),
                null, false, null, Instant.now());
            assertThat(true).as("expected NPE").isFalse();
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("diagnosis");
        }
    }

    @Test void remoteIncidentResultProviderCalledFalseWhenGated() {
        var discovery = new DiscoveryResult("inc-1", "run-1", "P1",
            minimalBundle("inc-1", "run-1"),
            DiscoveryStatus.INCOMPLETE, 0, 6, Instant.now());
        var diagnosis = new Diagnosis("INCONCLUSIVE", 0.0,
            List.of(), List.of(), List.of(),
            List.of(), "ESCALATE", false);
        var result = new RemoteIncidentResult(discovery, diagnosis, false,
            "DISCOVERY_INCOMPLETE", Instant.now());
        assertThat(result.providerCalled()).isFalse();
        assertThat(result.diagnosisGated()).isTrue();
        assertThat(result.diagnosisFailureCode()).isEqualTo("DISCOVERY_INCOMPLETE");
    }

    @Test void remoteIncidentResultDiagnosisGatedWhenNotCalled() {
        var result = new RemoteIncidentResult(
            new DiscoveryResult("inc-1", "run-1", "P1",
                minimalBundle("inc-1", "run-1"),
                DiscoveryStatus.INCOMPLETE, 0, 6, Instant.now()),
            new Diagnosis("INCONCLUSIVE", 0.0, List.of(), List.of(),
                List.of(), List.of(), "ESCALATE", false),
            false, "DISCOVERY_INCOMPLETE", Instant.now());
        assertThat(result.diagnosisGated()).isTrue();
        assertThat(result.isConclusive()).isFalse();
    }

    // ── Helpers ──

    private static void clearOpsEnv() {
        System.clearProperty("CLAWKIT_REMOTE_OPS_HOST");
        System.clearProperty("CLAWKIT_REMOTE_OPS_PORT");
        System.clearProperty("CLAWKIT_REMOTE_OPS_USER");
        System.clearProperty("CLAWKIT_REMOTE_OPS_IDENTITY_FILE");
        System.clearProperty("CLAWKIT_REMOTE_OPS_KNOWN_HOSTS");
        System.clearProperty("CLAWKIT_REMOTE_OPS_EXPECTED_PROFILE");
        System.clearProperty("CLAWKIT_REMOTE_OPS_EXPECTED_PROBE_VERSION");
        System.clearProperty("CLAWKIT_REMOTE_OPS_EXPECTED_TOOLSET_HASH");
    }
}
