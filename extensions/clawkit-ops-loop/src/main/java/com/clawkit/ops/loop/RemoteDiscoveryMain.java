package com.clawkit.ops.loop;

import com.clawkit.ops.mcp.OpsCapabilityProfile;
import com.clawkit.ops.mcp.OpsMcpServer;
import com.clawkit.provider.LLMConfig;
import com.clawkit.provider.LLMProvider;
import com.clawkit.provider.ProviderFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Manual entry point for a single-target remote discovery + diagnosis.
 *
 * <p>M2-0 Gate-0. Connects to the configured remote host via
 * {@link RemoteOpsSession}, collects evidence, runs diagnosis through
 * {@link DeepSeekDiagnosisGate}, and atomically persists a
 * {@link RemoteIncidentResult}.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — COMPLETE with diagnosis</li>
 *   <li>2 — Discovery incomplete</li>
 *   <li>3 — SSH/MCP failure</li>
 *   <li>4 — Config error</li>
 *   <li>5 — Persistence failure</li>
 *   <li>6 — Diagnosis / Provider unavailable</li>
 * </ul>
 */
public final class RemoteDiscoveryMain {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private RemoteDiscoveryMain() {}

    public static void main(String[] args) {
        int exit = run(args);
        System.exit(exit);
    }

    static int run(String[] args) {
        try {
            return runInternal(args);
        } catch (ConfigException e) {
            System.err.println(e.getMessage());
            return 4;
        }
    }

    private static int runInternal(String[] args) {
        // ── Parse args ──
        String targetId = null;
        String profileName = "REMOTE_APP_DOWN_V1";
        Path outputDir = Path.of(".");

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--target" -> targetId = args[++i];
                case "--profile" -> profileName = args[++i];
                case "--output" -> outputDir = Path.of(args[++i]);
                default -> {
                    System.err.println("usage: discover --target <id> [--profile <name>] [--output <dir>]");
                    return 4;
                }
            }
        }
        if (targetId == null) {
            System.err.println("--target is required");
            return 4;
        }

        // ── Resolve config from environment ──
        String host = require("CLAWKIT_REMOTE_OPS_HOST");
        String portStr = System.getenv().getOrDefault("CLAWKIT_REMOTE_OPS_PORT", "22");
        String user = require("CLAWKIT_REMOTE_OPS_USER");
        String identityPath = require("CLAWKIT_REMOTE_OPS_IDENTITY_FILE");
        String knownHostsPath = System.getenv().getOrDefault(
            "CLAWKIT_REMOTE_OPS_KNOWN_HOSTS",
            System.getProperty("user.home") + "/.ssh/known_hosts");
        String expectedProfile = require("CLAWKIT_REMOTE_OPS_EXPECTED_PROFILE");
        String expectedProbeVersion = System.getenv().getOrDefault(
            "CLAWKIT_REMOTE_OPS_EXPECTED_PROBE_VERSION", "1");

        int port;
        try { port = Integer.parseInt(portStr); } catch (NumberFormatException e) {
            System.err.println("invalid port: " + portStr);
            return 4;
        }

        // ── Build descriptor and config ──
        OpsCapabilityProfile profile;
        try {
            profile = OpsCapabilityProfile.fromEnvironment(expectedProfile);
        } catch (IllegalArgumentException e) {
            System.err.println("unknown profile: " + expectedProfile);
            return 4;
        }

        String expectedToolSetHash = System.getenv().getOrDefault(
            "CLAWKIT_REMOTE_OPS_EXPECTED_TOOLSET_HASH",
            OpsMcpServer.computeToolSetHash(profile));

        RemoteTargetDescriptor descriptor = new RemoteTargetDescriptor(
            targetId, profile.name(), expectedProbeVersion, expectedToolSetHash);

        // ── Build SshConnectionConfig ──
        Path idFile = Path.of(identityPath);
        Path khFile = Path.of(knownHostsPath);
        if (!Files.isRegularFile(idFile)) {
            System.err.println("identity file not found: " + idFile);
            return 4;
        }

        SshConnectionConfig connConfig;
        try {
            connConfig = new SshConnectionConfig(host, port, user,
                idFile, khFile, Duration.ofSeconds(15), Duration.ofSeconds(30), 65536);
        } catch (IllegalArgumentException e) {
            System.err.println("config error: " + e.getMessage());
            return 4;
        }

        // ── Select DiscoveryProfile ──
        DiscoveryProfile discoveryProfile;
        if ("APP_DOWN_V1".equals(profile.name())) {
            discoveryProfile = DiscoveryProfile.REMOTE_APP_DOWN_V1;
        } else if ("POSTGRES_DIAGNOSIS_V1".equals(profile.name())) {
            discoveryProfile = DiscoveryProfile.REMOTE_POSTGRES_DIAGNOSIS_V1;
        } else {
            System.err.println("no discovery profile for: " + profile.name());
            return 4;
        }

        // ── Execute discovery ──
        String incidentId = "inc-" + targetId + "-" + UUID.randomUUID().toString().substring(0, 8);
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        Clock clock = Clock.systemUTC();
        Instant startedAt = clock.instant();

        RemoteOpsSession session = new RemoteOpsSession(descriptor, connConfig, clock);
        try {
            session.start();
        } catch (IOException e) {
            System.err.println("session start failed: " + e.getMessage());
            return 3;
        }

        DiscoveryResult discovery;
        try {
            RemoteDiscoveryCoordinator coord = new RemoteDiscoveryCoordinator(session);
            discovery = coord.collect(incidentId, runId, discoveryProfile);
        } catch (IOException e) {
            System.err.println("discovery failed: " + e.getMessage());
            session.close();
            return 3;
        } finally {
            session.close();
        }

        // ── Diagnosis Gate ──
        Diagnosis diagnosis;
        boolean providerCalled = false;
        String diagnosisFailureCode = null;

        if (discovery.status() == DiscoveryStatus.COMPLETE) {
            String apiKey = System.getenv("CLAWKIT_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                // API key missing — keep Discovery, diagnose as INCONCLUSIVE
                System.err.println("CLAWKIT_API_KEY not set — diagnosis skipped");
                diagnosis = new Diagnosis("INCONCLUSIVE", 0.0,
                    List.of(), List.of(), List.of(), List.of(),
                    "ESCALATE", false);
                diagnosisFailureCode = "PROVIDER_NOT_CONFIGURED";
            } else {
                try {
                    LLMConfig llmConfig = LLMConfig.builder()
                        .apiKey(apiKey)
                        .build();
                    LLMProvider llmProvider = ProviderFactory.create(llmConfig);

                    DeepSeekDiagnosisGate gate = new DeepSeekDiagnosisGate(
                        llmProvider, llmConfig.model(), clock);

                    Duration diagnosisDeadline = Duration.ofSeconds(120);
                    diagnosis = gate.diagnose(discovery, null, diagnosisDeadline);
                    providerCalled = true;

                    if ("INCONCLUSIVE".equals(diagnosis.rootCauseCode())
                        && diagnosis.confidence() == 0.0) {
                        diagnosisFailureCode = "DIAGNOSIS_INCONCLUSIVE";
                    }
                } catch (Exception e) {
                    System.err.println("diagnosis failed: " + e.getMessage());
                    diagnosis = new Diagnosis("INCONCLUSIVE", 0.0,
                        List.of(), List.of(), List.of(), List.of(),
                        "ESCALATE", false);
                    diagnosisFailureCode = "PROVIDER_ERROR";
                }
            }
        } else {
            // Discovery incomplete or transport failed — skip Provider
            diagnosis = new Diagnosis("INCONCLUSIVE", 0.0,
                List.of(), List.of(), List.of(), List.of(),
                "ESCALATE", false);
            diagnosisFailureCode = discovery.status() == DiscoveryStatus.TRANSPORT_FAILED
                ? "TRANSPORT_FAILED" : "DISCOVERY_INCOMPLETE";
        }

        // ── Aggregate result ──
        RemoteIncidentResult result = new RemoteIncidentResult(
            discovery, diagnosis, providerCalled, diagnosisFailureCode,
            clock.instant());

        // ── Atomic persist ──
        try {
            Files.createDirectories(outputDir);
            Path target = outputDir.resolve("incident-" + runId + ".json");
            Path tmp = outputDir.resolve("incident-" + runId + ".json.tmp");

            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), result);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);

            System.out.println("incident result: " + target.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("failed to write output: " + e.getMessage());
            return 5;
        }

        // ── Exit code ──
        if (!providerCalled && diagnosisFailureCode != null
            && (diagnosisFailureCode.startsWith("PROVIDER_"))) {
            return 6;
        }
        return switch (discovery.status()) {
            case COMPLETE -> 0;
            case INCOMPLETE -> 2;
            case TRANSPORT_FAILED -> 3;
        };
    }

    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new ConfigException("missing required env: " + name);
        }
        return v;
    }

    /** Thrown for configuration errors; mapped to exit code 4. */
    static final class ConfigException extends RuntimeException {
        ConfigException(String msg) { super(msg); }
    }
}
