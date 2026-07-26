package com.clawkit.ops.loop;

import com.clawkit.ops.mcp.OpsCapabilityProfile;
import com.clawkit.ops.mcp.OpsMcpServer;
import com.clawkit.provider.LLMConfig;
import com.clawkit.provider.LLMProvider;
import com.clawkit.provider.ProviderFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reusable remote discovery + diagnosis workflow.
 *
 * <p>R3. Extracted from {@link RemoteDiscoveryMain} so that delivery
 * pipelines (report, notification) can reuse the same core without
 * CLI concerns.
 */
public final class RemoteDiscoveryWorkflow {

    private static final Logger log = LoggerFactory.getLogger(RemoteDiscoveryWorkflow.class);

    public record Config(
        String targetId,
        String host,
        int port,
        String user,
        Path identityFile,
        Path knownHostsFile,
        String capabilityProfile,
        String expectedProbeVersion,
        String expectedToolSetHash,
        String apiKey,
        Duration diagnosisDeadline
    ) {
        public static final Duration DEFAULT_DIAGNOSIS_DEADLINE = Duration.ofSeconds(120);
    }

    private final Config config;
    private final Clock clock;

    public RemoteDiscoveryWorkflow(Config config) {
        this(config, Clock.systemUTC());
    }

    public RemoteDiscoveryWorkflow(Config config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /**
     * Execute discovery + diagnosis.
     *
     * @return aggregated result (never null — diagnosis is INCONCLUSIVE on failure)
     */
    public RemoteIncidentResult execute() {
        // ── Build descriptor ──
        OpsCapabilityProfile profile = OpsCapabilityProfile.fromEnvironment(
            config.capabilityProfile());

        String toolSetHash = config.expectedToolSetHash() != null
            ? config.expectedToolSetHash()
            : OpsMcpServer.computeToolSetHash(profile);

        RemoteTargetDescriptor descriptor = new RemoteTargetDescriptor(
            config.targetId(), profile.name(),
            config.expectedProbeVersion(), toolSetHash);

        // ── Build connection config ──
        SshConnectionConfig connConfig = new SshConnectionConfig(
            config.host(), config.port(), config.user(),
            config.identityFile(), config.knownHostsFile(),
            Duration.ofSeconds(15), Duration.ofSeconds(30), 65536);

        // ── Discovery ──
        DiscoveryProfile discoveryProfile = selectProfile(profile.name());
        String incidentId = "inc-" + config.targetId() + "-"
            + UUID.randomUUID().toString().substring(0, 8);
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);

        DiscoveryResult discovery;
        try (RemoteOpsSession session = new RemoteOpsSession(descriptor, connConfig, clock)) {
            session.start();
            RemoteDiscoveryCoordinator coord = new RemoteDiscoveryCoordinator(session);
            discovery = coord.collect(incidentId, runId, discoveryProfile);
        } catch (IOException e) {
            log.error("Discovery failed: {}", e.getMessage());
            // Fake a transport-failed discovery result for downstream
            var emptyBundle = new EvidenceBundle(incidentId, runId, clock.instant(),
                List.of(new Evidence("e-0", incidentId, EvidenceType.SERVICE_STATUS,
                    "mcp:ops/error", clock.instant(), clock.instant(), "error",
                    Evidence.Kind.FACT, new com.fasterxml.jackson.databind.ObjectMapper()
                        .createObjectNode().put("success", false).put("error", e.getMessage()),
                    "run://" + runId + "/e-0", Evidence.Freshness.STALE,
                    Evidence.Redaction.NONE, "2",
                    Evidence.CollectionStatus.COLLECTION_FAILED, null, null)));
            discovery = new DiscoveryResult(incidentId, runId, profile.name(),
                emptyBundle, DiscoveryStatus.TRANSPORT_FAILED, 0, 0, clock.instant());
        }

        // ── Diagnosis ──
        Diagnosis diagnosis;
        boolean providerCalled = false;
        String diagnosisFailureCode = null;

        if (discovery.status() == DiscoveryStatus.COMPLETE) {
            String apiKey = config.apiKey();
            if (apiKey == null || apiKey.isBlank()) {
                diagnosis = inconclusiveDiagnosis();
                diagnosisFailureCode = "PROVIDER_NOT_CONFIGURED";
            } else {
                try {
                    LLMConfig llmConfig = LLMConfig.builder().apiKey(apiKey).build();
                    LLMProvider llmProvider = ProviderFactory.create(llmConfig);
                    DeepSeekDiagnosisGate gate = new DeepSeekDiagnosisGate(
                        llmProvider, llmConfig.model(), clock);
                    Duration deadline = config.diagnosisDeadline() != null
                        ? config.diagnosisDeadline() : Config.DEFAULT_DIAGNOSIS_DEADLINE;
                    diagnosis = gate.diagnose(discovery, null, deadline);
                    providerCalled = true;
                    if ("INCONCLUSIVE".equals(diagnosis.rootCauseCode())
                        && diagnosis.confidence() == 0.0) {
                        diagnosisFailureCode = "DIAGNOSIS_INCONCLUSIVE";
                    }
                } catch (Exception e) {
                    log.error("Diagnosis failed: {}", e.getMessage());
                    diagnosis = inconclusiveDiagnosis();
                    diagnosisFailureCode = "PROVIDER_ERROR";
                }
            }
        } else {
            diagnosis = inconclusiveDiagnosis();
            diagnosisFailureCode = discovery.status() == DiscoveryStatus.TRANSPORT_FAILED
                ? "TRANSPORT_FAILED" : "DISCOVERY_INCOMPLETE";
        }

        return new RemoteIncidentResult(discovery, diagnosis, providerCalled,
            diagnosisFailureCode, clock.instant());
    }

    private static DiscoveryProfile selectProfile(String name) {
        if ("APP_DOWN_V1".equals(name)) return DiscoveryProfile.REMOTE_APP_DOWN_V1;
        if ("POSTGRES_DIAGNOSIS_V1".equals(name)) return DiscoveryProfile.REMOTE_POSTGRES_DIAGNOSIS_V1;
        throw new IllegalArgumentException("no discovery profile for: " + name);
    }

    private static Diagnosis inconclusiveDiagnosis() {
        return new Diagnosis("INCONCLUSIVE", 0.0,
            List.of(), List.of(), List.of(), List.of(), "ESCALATE", false);
    }
}
