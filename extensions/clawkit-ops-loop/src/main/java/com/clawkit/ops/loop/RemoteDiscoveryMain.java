package com.clawkit.ops.loop;

import com.clawkit.ops.mcp.OpsCapabilityProfile;
import com.clawkit.ops.mcp.OpsMcpServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Manual entry point for a single-target remote discovery.
 *
 * <p>PR-M4 §6. Connects to the configured remote host via
 * {@link RemoteOpsSession}, collects evidence per the selected
 * {@link DiscoveryProfile}, and persists results.
 *
 * <p>Exit codes: 0=COMPLETE, 2=INCOMPLETE, 3=SSH/MCP failure,
 * 4=config error, 5=persistence failure.
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

        // ── Resolve config from environment (§6.1) ──
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

        RemoteOpsSession session = new RemoteOpsSession(descriptor, connConfig, Clock.systemUTC());
        try {
            session.start();
        } catch (IOException e) {
            System.err.println("session start failed: " + e.getMessage());
            return 3;
        }

        DiscoveryResult result;
        try {
            RemoteDiscoveryCoordinator coord = new RemoteDiscoveryCoordinator(session);
            result = coord.collect(incidentId, runId, discoveryProfile);
        } catch (IOException e) {
            System.err.println("discovery failed: " + e.getMessage());
            session.close();
            return 3;
        } finally {
            session.close();
        }

        // ── Persist result ──
        try {
            Files.createDirectories(outputDir);
            Path output = outputDir.resolve("discovery-" + runId + ".json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
            System.out.println("discovery complete: " + output.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("failed to write output: " + e.getMessage());
            return 5;
        }

        // ── Exit code by status ──
        return switch (result.status()) {
            case COMPLETE -> 0;
            case INCOMPLETE -> 2;
            case TRANSPORT_FAILED -> 3;
        };
    }

    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            System.err.println("missing required env: " + name);
            System.exit(4);
        }
        return v;
    }
}
