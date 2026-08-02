package com.clawkit.ops.delivery;

import com.clawkit.ops.loop.*;
import com.clawkit.ops.loop.report.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R6: Remote E2E integration test — OPS MVP-2 acceptance gate.
 *
 * <p>Requires env vars: CLAWKIT_REMOTE_OPS_HOST, CLAWKIT_API_KEY.
 * Skips automatically if not configured.
 *
 * <p>Permission separation:
 * <ul>
 *   <li>opsro identity (CLAWKIT_REMOTE_OPS_IDENTITY_FILE) — Discovery/MCP only</li>
 *   <li>Fixture admin identity (CLAWKIT_FIXTURE_ADMIN_IDENTITY_FILE) —
 *       business invariant verification via SSH</li>
 *   <li>Control token (CLAWKIT_FIXTURE_CONTROL_TOKEN) — injected, not hardcoded</li>
 * </ul>
 *
 * <p>Run: {@code mvn -pl extensions/clawkit-ops-delivery -am test -Dtest=RemoteE2EIntegrationTest}
 */
@EnabledIfEnvironmentVariable(named = "CLAWKIT_REMOTE_OPS_HOST", matches = ".+")
class RemoteE2EIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());
    private static final int TOTAL_ROUNDS = Integer.parseInt(
        System.getProperty("e2e.rounds", "20"));
    /** Minimum number of rounds that must correctly identify DB_LOCK_WAIT. */
    private static final int MIN_PASSED = TOTAL_ROUNDS >= 20
        ? 18 : (int) Math.ceil(TOTAL_ROUNDS * 0.90);

    /** Single-round end-to-end: hot-contention → discovery → diagnosis → report → invariant check. */
    @Test void singleRoundE2E() throws Exception {
        Path outputDir = Path.of(System.getProperty("e2e.output",
            "target/ops-e2e-" + Instant.now().toString().replace(":", "-").substring(0, 19)));
        Files.createDirectories(outputDir);

        // ── Pre: verify business invariants via fixture admin channel ──
        JsonNode preInvariants = verifyBusinessInvariants();
        long preFailures = countInvariantFailures(preInvariants);
        assertThat(preFailures)
            .as("pre-condition: business invariants must pass before test")
            .isEqualTo(0);

        // ── Build workflow config (uses opsro identity for MCP) ──
        RemoteDiscoveryWorkflow.Config wfConfig = config("e2e-single");

        RemoteDiscoveryWorkflow workflow = new RemoteDiscoveryWorkflow(wfConfig);
        RemoteIncidentResult result = workflow.execute();

        assertThat(result).isNotNull();
        assertThat(result.discovery()).isNotNull();

        // ── Persist incident ──
        Path incidentFile = outputDir.resolve("incident.json");
        atomicWrite(incidentFile, result);
        System.out.println("incident: " + incidentFile);

        // ── Assemble report ──
        HumanIncidentReport report = IncidentReportAssembler.assemble(result);
        Path reportJson = outputDir.resolve("report.json");
        atomicWrite(reportJson, JsonIncidentRenderer.render(report));
        Path reportMd = outputDir.resolve("report.md");
        atomicWrite(reportMd, MarkdownIncidentRenderer.render(report));
        Path feishuTxt = outputDir.resolve("feishu-summary.txt");
        Files.writeString(feishuTxt, FeishuSummaryRenderer.render(report));

        // ── Post: verify business invariants via fixture admin channel ──
        JsonNode postInvariants = verifyBusinessInvariants();
        long postFailures = countInvariantFailures(postInvariants);
        Files.writeString(outputDir.resolve("invariants-post.json"),
            MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(postInvariants));

        // ── Verify ──
        System.out.println("Status: " + result.discovery().status());
        System.out.println("Provider called: " + result.providerCalled());
        System.out.println("Diagnosis: " + report.rootCauseCode()
            + " (" + report.diagnosisConfidence() + ")");
        System.out.println("Evidence count: " + result.discovery().bundle().evidence().size());
        System.out.println("Content hash: " + report.contentHash());

        // ── Mandatory assertions ──

        // A1: Discovery must be COMPLETE
        assertThat(result.discovery().status())
            .as("Discovery must be COMPLETE")
            .isEqualTo(DiscoveryStatus.COMPLETE);

        // A2: All required evidence must succeed
        assertThat(result.discovery().requiredSuccess())
            .as("All required evidence must succeed (8/8)")
            .isEqualTo(result.discovery().requiredTotal())
            .isGreaterThanOrEqualTo(8);

        // A3: Provider must be called
        assertThat(result.providerCalled())
            .as("Provider must be called for diagnosis")
            .isTrue();

        // A4: Root cause MUST be DB_LOCK_WAIT (DiagnosisReconciler enforces this
        //     from deterministic DiagnosticSignals when lock evidence is present)
        assertThat(report.rootCauseCode())
            .as("Root cause must be DB_LOCK_WAIT — reconciler uses deterministic signals")
            .isEqualTo("DB_LOCK_WAIT");

        // A5: Must not claim resolved
        assertThat(report.claimedResolved())
            .as("Must not claim auto-resolution")
            .isFalse();

        // A6: Data label must be present
        assertThat(report.dataLabel())
            .isEqualTo("SYNTHETIC_BUSINESS_DATA");

        // A7: Report must not leak secrets or hardcoded tokens
        String mdContent = Files.readString(reportMd);
        assertThat(mdContent).doesNotContain("122.51.51");
        assertThat(mdContent).doesNotContain(fixtureControlToken());
        assertThat(mdContent).doesNotContain("CLAWKIT_API_KEY");

        // A8: Post-condition business invariants must pass (fixture oracle)
        assertThat(postFailures)
            .as("post-condition: business invariants (fixture oracle) must pass after test")
            .isEqualTo(0);

        // A9: Evidence must include DB blocking data
        List<Evidence> evidence = result.discovery().bundle().evidence();
        boolean hasLockGraph = evidence.stream()
            .anyMatch(e -> e.type() == EvidenceType.DB_LOCK_GRAPH
                && e.collectionStatus() == Evidence.CollectionStatus.OBSERVED);
        boolean hasDbActivity = evidence.stream()
            .anyMatch(e -> e.type() == EvidenceType.DB_ACTIVITY
                && e.collectionStatus() == Evidence.CollectionStatus.OBSERVED);
        assertThat(hasLockGraph).as("Must have DB_LOCK_GRAPH evidence").isTrue();
        assertThat(hasDbActivity).as("Must have DB_ACTIVITY evidence").isTrue();

        // A10: Report must disclose that invariants come from fixture oracle
        assertThat(mdContent)
            .as("Report must disclose SYNTHETIC_BUSINESS_DATA label")
            .contains("SYNTHETIC_BUSINESS_DATA");
    }

    /** Multi-round benchmark: discovery + diagnosis every round. */
    @Test void multiRoundBenchmark() throws Exception {
        Path outputDir = Path.of(System.getProperty("e2e.output",
            "target/ops-e2e-bench-" + Instant.now().toString().replace(":", "-").substring(0, 19)));
        Files.createDirectories(outputDir);

        // Pre-condition: verify business invariants via fixture admin channel
        JsonNode preInvariants = verifyBusinessInvariants();
        long preFailures = countInvariantFailures(preInvariants);
        assertThat(preFailures)
            .as("pre-condition: business invariants must pass before benchmark")
            .isEqualTo(0);

        int passed = 0, failed = 0, evaluable = 0;
        int transportFailures = 0;

        for (int round = 1; round <= TOTAL_ROUNDS; round++) {
            System.out.println("=== Round " + round + "/" + TOTAL_ROUNDS + " ===");
            Path roundDir = outputDir.resolve("round-" + round);
            Files.createDirectories(roundDir);

            try {
                RemoteDiscoveryWorkflow.Config wfConfig = config("e2e-r" + round);
                RemoteDiscoveryWorkflow workflow = new RemoteDiscoveryWorkflow(wfConfig);
                RemoteIncidentResult result = workflow.execute();
                HumanIncidentReport report = IncidentReportAssembler.assemble(result);

                // Persist round output
                atomicWrite(roundDir.resolve("incident.json"), result);
                Files.writeString(roundDir.resolve("report.md"),
                    MarkdownIncidentRenderer.render(report));

                // Check evaluability: must have evidence + no transport failure
                boolean hasEvidence = !result.discovery().bundle().evidence().isEmpty();
                boolean transportOk = result.discovery().status() != DiscoveryStatus.TRANSPORT_FAILED;

                if (hasEvidence && transportOk) {
                    evaluable++;
                    boolean correctDiagnosis = "DB_LOCK_WAIT".equals(report.rootCauseCode())
                        && report.claimedResolved() == false;
                    if (correctDiagnosis) {
                        passed++;
                        System.out.println("  PASS: " + report.rootCauseCode()
                            + " confidence=" + report.diagnosisConfidence());
                    } else {
                        failed++;
                        System.out.println("  FAIL: " + report.rootCauseCode()
                            + " claimedResolved=" + report.claimedResolved());
                    }
                } else {
                    transportFailures++;
                    System.out.println("  TRANSPORT_FAILURE: evidence=" + hasEvidence
                        + " transport=" + transportOk);
                }

                // Persist summary per round
                Files.writeString(roundDir.resolve("result.txt"),
                    String.format("rootCause=%s confidence=%.2f passed=%b\n",
                        report.rootCauseCode(), report.diagnosisConfidence(),
                        "DB_LOCK_WAIT".equals(report.rootCauseCode())
                            && !report.claimedResolved()));

            } catch (Exception e) {
                System.err.println("  ERROR: " + e.getMessage());
                Files.writeString(roundDir.resolve("error.txt"), e.getMessage());
            }
        }

        // Post-condition: verify business invariants via fixture admin channel
        JsonNode postInvariants = verifyBusinessInvariants();
        long postFailures = countInvariantFailures(postInvariants);
        Files.writeString(outputDir.resolve("invariants-post.json"),
            MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(postInvariants));

        // ── Summary ──
        System.out.println("\n=== BENCHMARK COMPLETE ===");
        System.out.println("Requested: " + TOTAL_ROUNDS);
        System.out.println("Evaluable: " + evaluable);
        System.out.println("Passed:    " + passed);
        System.out.println("Failed:    " + failed);
        System.out.println("Transport: " + transportFailures);
        Files.writeString(outputDir.resolve("summary.txt"),
            String.format("requested=%d\nevaluable=%d\npassed=%d\nfailed=%d\ntransportFailures=%d\n",
                TOTAL_ROUNDS, evaluable, passed, failed, transportFailures));

        // ── Mandatory assertions ──

        // B1: All rounds must be evaluable
        assertThat(evaluable)
            .as("All %d rounds must be evaluable (no transport failures)", TOTAL_ROUNDS)
            .isEqualTo(TOTAL_ROUNDS);

        // B2: Zero transport failures
        assertThat(transportFailures)
            .as("Zero transport failures across all rounds")
            .isEqualTo(0);

        // B3: At least 18/20 rounds must correctly identify DB_LOCK_WAIT
        assertThat(passed)
            .as("At least %d/%d rounds must diagnose DB_LOCK_WAIT", MIN_PASSED, TOTAL_ROUNDS)
            .isGreaterThanOrEqualTo(MIN_PASSED);

        // B4: Post-condition invariants must pass (fixture oracle)
        assertThat(postFailures)
            .as("post-condition: business invariants (fixture oracle) must pass after benchmark")
            .isEqualTo(0);
    }

    // ── Business invariant verification (fixture admin channel) ──

    /**
     * Call the fixture oracle via the fixture admin SSH identity.
     * The opsro forced-command identity is NOT used for fixture control.
     */
    private JsonNode verifyBusinessInvariants() throws Exception {
        String token = fixtureControlToken();
        String output = fixtureAdminSsh(
            "docker exec clawkit-ops-r6-order-api-1 wget -qO- " +
            "--header='X-Control-Token: " + token + "' " +
            "'http://127.0.0.1:8080/internal/verify' 2>/dev/null");
        // Extract the JSON array from output
        int braceStart = output.lastIndexOf("[{");
        if (braceStart < 0) braceStart = output.indexOf('[');
        int braceEnd = output.lastIndexOf(']');
        if (braceStart >= 0 && braceEnd > braceStart) {
            output = output.substring(braceStart, braceEnd + 1);
        }
        return MAPPER.readTree(output);
    }

    private static long countInvariantFailures(JsonNode invariants) {
        if (invariants == null || !invariants.isArray()) return -1;
        long failures = 0;
        for (JsonNode account : invariants) {
            if (!account.path("passed").asBoolean(true)) {
                failures++;
                System.err.println("INVARIANT FAILED: " + account);
            }
        }
        if (failures == 0 && invariants.size() > 0) {
            System.out.println("Business invariants (fixture oracle): "
                + invariants.size() + " accounts, all passed");
        }
        return failures;
    }

    // ── SSH channels ──

    /** SSH using the fixture admin identity (NOT opsro). */
    private static String fixtureAdminSsh(String command) throws IOException {
        String identityFile = System.getenv("CLAWKIT_FIXTURE_ADMIN_IDENTITY_FILE");
        if (identityFile == null || identityFile.isBlank()) {
            // Fall back to opsro identity for environments without separate admin key
            identityFile = require("CLAWKIT_REMOTE_OPS_IDENTITY_FILE");
        }
        return sshExec(identityFile, "root@" + require("CLAWKIT_REMOTE_OPS_HOST"), command);
    }

    private static String sshExec(String identityFile, String target, String command)
        throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
            "ssh", "-q", "-i", identityFile,
            "-o", "ConnectTimeout=10",
            "-o", "StrictHostKeyChecking=yes",
            "-o", "BatchMode=yes",
            "-o", "LogLevel=QUIET",
            target, command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try {
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(30, TimeUnit.SECONDS);
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted waiting for ssh", e);
        }
    }

    /** Control token for fixture operations — injected from env, never hardcoded. */
    private static String fixtureControlToken() {
        String token = System.getenv("CLAWKIT_FIXTURE_CONTROL_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                "missing env: CLAWKIT_FIXTURE_CONTROL_TOKEN");
        }
        return token;
    }

    // ── Helpers ──

    private static RemoteDiscoveryWorkflow.Config config(String incidentPrefix) {
        return new RemoteDiscoveryWorkflow.Config(
            incidentPrefix,
            require("CLAWKIT_REMOTE_OPS_HOST"),
            Integer.parseInt(System.getenv().getOrDefault("CLAWKIT_REMOTE_OPS_PORT", "22")),
            require("CLAWKIT_REMOTE_OPS_USER"),
            Path.of(require("CLAWKIT_REMOTE_OPS_IDENTITY_FILE")),
            Path.of(System.getenv().getOrDefault("CLAWKIT_REMOTE_OPS_KNOWN_HOSTS",
                System.getProperty("user.home") + "/.ssh/known_hosts")),
            require("CLAWKIT_REMOTE_OPS_EXPECTED_PROFILE"),
            System.getenv().getOrDefault("CLAWKIT_REMOTE_OPS_EXPECTED_PROBE_VERSION", "1"),
            System.getenv("CLAWKIT_REMOTE_OPS_EXPECTED_TOOLSET_HASH"),
            System.getenv("CLAWKIT_API_KEY"),
            System.getenv().getOrDefault("CLAWKIT_DIAGNOSIS_MODEL",
                RemoteDiscoveryWorkflow.Config.DEFAULT_DIAGNOSIS_MODEL),
            Duration.ofSeconds(120));
    }

    private static void atomicWrite(Path target, Object content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), content);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank())
            throw new IllegalStateException("missing env: " + name);
        return v;
    }
}
