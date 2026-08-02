package com.clawkit.ops.delivery;

import com.clawkit.ops.loop.*;
import com.clawkit.ops.loop.repair.*;
import com.clawkit.reliability.attempt.AttemptState;
import com.clawkit.reliability.attempt.FileActionAttemptStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class RemoteIncidentRepairMain {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private RemoteIncidentRepairMain() {}

    public static void main(String[] args) { System.exit(run(args)); }

    static int run(String[] args) {
        String targetId = null; Path outputDir = Path.of("."); boolean autoApprove = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--target" -> targetId = nextArg(args, i++);
                case "--output" -> outputDir = Path.of(nextArg(args, i++));
                case "--auto-approve" -> autoApprove = true;
                default -> { System.err.println("usage: repair --target <id> [--output <dir>] [--auto-approve]"); return 4; }
            }
        }
        if (targetId == null) { System.err.println("--target required"); return 4; }
        try { Files.createDirectories(outputDir); } catch (IOException e) { System.err.println("output dir: " + e.getMessage()); return 5; }

        Path eventsFile = outputDir.resolve("lifecycle-events.ndjson");
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        String canonicalTarget = "target:" + targetId;
        var seq = new AtomicInteger(0);
        FileActionAttemptStore attemptStore = null;

        try (BufferedWriter eventWriter = Files.newBufferedWriter(eventsFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            // Build observer that writes to NDJSON
            RepairLifecycleObserver fileObserver = event -> {
                try {
                    ObjectNode e = MAPPER.createObjectNode();
                    e.put("schemaVersion", "1");
                    e.put("sequence", event.sequence());
                    e.put("timestamp", event.timestamp().toString());
                    e.put("runId", event.runId());
                    if (event.incidentId() != null) e.put("incidentId", event.incidentId());
                    if (event.attemptId() != null) e.put("attemptId", event.attemptId());
                    e.put("stage", event.stage());
                    e.put("status", event.status());
                    e.put("detail", event.detail() != null ? event.detail() : "");
                    eventWriter.write(MAPPER.writeValueAsString(e));
                    eventWriter.newLine();
                    eventWriter.flush();
                } catch (IOException ignored) { }
            };

            attemptStore = new FileActionAttemptStore(outputDir.resolve(".attempts"));

            emit(fileObserver, seq, runId, null, null, "discovery_started", "in_progress", "");
            RemoteDiscoveryWorkflow.Config wf = buildConfig(targetId);
            RemoteIncidentResult result = new RemoteDiscoveryWorkflow(wf).execute();
            String rid = result.discovery().runId();
            String incidentId = result.discovery().incidentId();
            emit(fileObserver, seq, runId, incidentId, null, "discovery_complete",
                result.discovery().isComplete() ? "ok" : "incomplete",
                "status=" + result.discovery().status());

            Diagnosis diagnosis = result.diagnosis();
            boolean evidenceOverride = false;
            if ("INCONCLUSIVE".equals(diagnosis.rootCauseCode())) {
                if (detectAppDownFromDiscovery(result.discovery())) {
                    diagnosis = new Diagnosis("APP_DOWN", 0.95,
                        diagnosis.supportingEvidence(), diagnosis.contradictingEvidence(),
                        diagnosis.alternatives(), diagnosis.missingEvidence(),
                        "RESTART_SERVICE", false, "2",
                        Diagnosis.DiagnosisStatus.CONFIRMED, Diagnosis.CurrentCondition.ACTIVE,
                        Instant.now(), Diagnosis.ResolutionAttribution.NONE);
                    evidenceOverride = true;
                }
            }
            emit(fileObserver, seq, runId, incidentId, null, "diagnosis_complete",
                "ok", "rootCause=" + diagnosis.rootCauseCode() + " evidenceOverride=" + evidenceOverride);

            RepairOrchestrator orchestrator = new RepairOrchestrator(attemptStore, java.time.Clock.systemUTC(), fileObserver);
            RepairSuggestion suggestion = orchestrator.generateSuggestion(diagnosis, result.discovery());
            GateDecision gate = RepairPolicyGate.evaluate(diagnosis, suggestion);
            emit(fileObserver, seq, runId, incidentId, null, "policy_gate_evaluated",
                gate.allowed() ? "ok" : "denied", "allowed=" + gate.allowed());
            if (gate.denied()) { writeRunManifest(outputDir, runId, incidentId, null, "blocked", null, null); return 2; }

            String initialSnapshot = SnapshotHasher.compute(result.discovery().bundle(), suggestion.serviceId());
            var descriptor = RepairAction.RESTART_SERVICE.toActionDescriptor(canonicalTarget, suggestion.serviceId(), initialSnapshot);
            RepairCliHandler cli = new RepairCliHandler();
            ApprovalGrant grant = autoApprove
                ? cli.autoApprove(incidentId, canonicalTarget, descriptor, initialSnapshot)
                : cli.requestApproval(incidentId, canonicalTarget, descriptor, initialSnapshot, "cli-user");
            if (grant == null) { emit(fileObserver, seq, runId, incidentId, null, "repair_aborted", "blocked", "no approval"); return 3; }
            emit(fileObserver, seq, runId, incidentId, null, "approval_granted", "ok", "grantId=" + grant.grantId());

            String repairRunId = "repair-" + UUID.randomUUID().toString().substring(0, 8);
            SshConnectionConfig opsroConfig = buildOpsroConfig(targetId);
            RemoteTargetDescriptor opsroTarget = buildOpsroTarget(targetId);
            SshConnectionConfig fixConfig = buildFixConfig(targetId);
            RemoteTargetDescriptor fixTarget = new RemoteTargetDescriptor(targetId, "FIX_ORDER_API_V1", "1", "bee3a68b688ec39a");

            RepairResult repairResult;
            try (RemoteOpsSession precheckSession = new RemoteOpsSession(opsroTarget, opsroConfig);
                 OpsFixSession fixSession = new OpsFixSession(fixTarget, fixConfig)) {
                precheckSession.start(); fixSession.start();
                repairResult = orchestrator.executeApprovedRepair(grant, RepairAction.RESTART_SERVICE,
                    "order-api", canonicalTarget, precheckSession, incidentId, fixSession, repairRunId);
            }

            AttemptState state = repairResult.attemptState();
            String attemptId = repairResult.attemptId();
            emit(fileObserver, seq, runId, incidentId, attemptId, "attempt_outcome",
                state == AttemptState.VERIFYING ? "ok" : state == AttemptState.OUTCOME_UNKNOWN ? "unknown" : "failed",
                "state=" + state);

            VerificationResult vr = null;
            if (state == AttemptState.VERIFYING) {
                emit(fileObserver, seq, runId, incidentId, attemptId, "verification_started", "in_progress", "new opsro session");
                RemoteTargetDescriptor verifyTarget = buildOpsroTarget(targetId);
                SshConnectionConfig verifyConfig = buildOpsroConfig(targetId);
                IndependentVerifier verifier = new IndependentVerifier();
                vr = verifier.verify(repairResult, incidentId, verifyConfig, verifyTarget);
                repairResult = repairResult.withVerification(vr);
                repairResult = orchestrator.completeVerification(repairResult, vr.passed(),
                    vr.passed() ? "independent verification passed" : "verification failed: " + String.join("; ", vr.failureReasons()));
                if (vr.passed()) {
                    emit(fileObserver, seq, runId, incidentId, attemptId, "independent_verification_passed", "ok",
                        "checks=" + vr.checks().size() + " bizOk=" + vr.businessInvariantsPassed());
                    if (repairResult.isSuccess()) {
                        emit(fileObserver, seq, runId, incidentId, attemptId, "repair_verified_success", "ok",
                            "VERIFIED_SUCCESS");
                    }
                } else {
                    emit(fileObserver, seq, runId, incidentId, attemptId, "independent_verification_passed", "failed",
                        String.join("; ", vr.failureReasons()));
                }
                System.out.println("verification: " + (vr.passed() ? "PASSED" : "FAILED") + " state=" + repairResult.attemptState());
            } else if (state == AttemptState.OUTCOME_UNKNOWN) {
                System.out.println("repair outcome UNKNOWN — no auto-retry");
            }

            Path rf = outputDir.resolve("repair-result.json");
            RemoteIncidentDeliveryMain.atomicWriteJson(rf, repairResult);
            writeRunManifest(outputDir, runId, incidentId, attemptId, repairResult.attemptState().name(),
                repairRunId, vr != null ? vr.verificationRunId() : null);
            System.out.println("repair-result: " + rf.toAbsolutePath());
            return repairResult.isSuccess() ? 0 : 2;

        } catch (RemoteDiscoveryMain.ConfigException e) {
            System.err.println(e.getMessage()); return 4;
        } catch (Exception e) {
            System.err.println("repair: " + e.getMessage()); e.printStackTrace(); return 3;
        } finally {
            if (attemptStore != null) { try { attemptStore.close(); } catch (Exception ignored) { } }
        }
    }

    private static void emit(RepairLifecycleObserver obs, AtomicInteger seq, String runId,
            String incidentId, String attemptId, String stage, String status, String detail) {
        obs.onEvent(new RepairLifecycleObserver.RepairLifecycleEvent(
            seq.incrementAndGet(), Instant.now(), runId, incidentId, attemptId, stage, status, detail));
    }

    private static void writeRunManifest(Path dir, String runId, String incidentId, String attemptId,
            String attemptState, String repairRunId, String verificationRunId) {
        try {
            ObjectNode m = MAPPER.createObjectNode();
            m.put("schemaVersion", "1"); m.put("runId", runId);
            m.put("incidentId", nz(incidentId)); m.put("attemptId", nz(attemptId));
            m.put("attemptState", nz(attemptState));
            m.put("repairRunId", nz(repairRunId)); m.put("verificationRunId", nz(verificationRunId));
            m.put("completedAt", Instant.now().toString());
            Path tmp = dir.resolve("run-manifest.json.tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), m);
            Files.move(tmp, dir.resolve("run-manifest.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { System.err.println("manifest: " + e.getMessage()); }
    }
    private static String nz(String s) { return s != null ? s : ""; }

    private static RemoteDiscoveryWorkflow.Config buildConfig(String targetId) {
        return new RemoteDiscoveryWorkflow.Config(targetId,
            require("CLAWKIT_REMOTE_OPS_HOST"),
            Integer.parseInt(System.getenv().getOrDefault("CLAWKIT_REMOTE_OPS_PORT", "22")),
            require("CLAWKIT_REMOTE_OPS_USER"),
            Path.of(require("CLAWKIT_REMOTE_OPS_IDENTITY_FILE")),
            Path.of(System.getenv().getOrDefault("CLAWKIT_REMOTE_OPS_KNOWN_HOSTS", System.getProperty("user.home") + "/.ssh/known_hosts")),
            require("CLAWKIT_REMOTE_OPS_EXPECTED_PROFILE"),
            System.getenv().getOrDefault("CLAWKIT_REMOTE_OPS_EXPECTED_PROBE_VERSION", "1"),
            System.getenv("CLAWKIT_REMOTE_OPS_EXPECTED_TOOLSET_HASH"),
            System.getenv("CLAWKIT_API_KEY"),
            System.getenv().getOrDefault("CLAWKIT_DIAGNOSIS_MODEL", RemoteDiscoveryWorkflow.Config.DEFAULT_DIAGNOSIS_MODEL),
            Duration.ofSeconds(120));
    }
    private static SshConnectionConfig buildFixConfig(String targetId) {
        return new SshConnectionConfig(require("CLAWKIT_REMOTE_FIX_HOST"),
            Integer.parseInt(System.getenv().getOrDefault("CLAWKIT_REMOTE_FIX_PORT", System.getenv().getOrDefault("CLAWKIT_REMOTE_OPS_PORT", "22"))),
            require("CLAWKIT_REMOTE_FIX_USER"), Path.of(require("CLAWKIT_REMOTE_FIX_IDENTITY_FILE")),
            Path.of(System.getenv().getOrDefault("CLAWKIT_REMOTE_FIX_KNOWN_HOSTS", System.getProperty("user.home") + "/.ssh/known_hosts")),
            Duration.ofSeconds(15), Duration.ofSeconds(30), 65536);
    }
    private static SshConnectionConfig buildOpsroConfig(String targetId) {
        return new SshConnectionConfig(require("CLAWKIT_REMOTE_OPS_HOST"),
            Integer.parseInt(System.getenv().getOrDefault("CLAWKIT_REMOTE_OPS_PORT", "22")),
            require("CLAWKIT_REMOTE_OPS_USER"), Path.of(require("CLAWKIT_REMOTE_OPS_IDENTITY_FILE")),
            Path.of(System.getenv().getOrDefault("CLAWKIT_REMOTE_OPS_KNOWN_HOSTS", System.getProperty("user.home") + "/.ssh/known_hosts")),
            Duration.ofSeconds(15), Duration.ofSeconds(30), 65536);
    }
    private static RemoteTargetDescriptor buildOpsroTarget(String targetId) {
        return new RemoteTargetDescriptor(targetId, require("CLAWKIT_REMOTE_OPS_EXPECTED_PROFILE"),
            System.getenv().getOrDefault("CLAWKIT_REMOTE_OPS_EXPECTED_PROBE_VERSION", "1"),
            System.getenv("CLAWKIT_REMOTE_OPS_EXPECTED_TOOLSET_HASH"));
    }
    private static String nextArg(String[] args, int i) {
        if (i + 1 >= args.length) throw new RemoteDiscoveryMain.ConfigException("missing value for " + args[i]);
        return args[i + 1];
    }
    private static boolean detectAppDownFromDiscovery(DiscoveryResult discovery) {
        for (var item : discovery.bundle().evidence()) {
            if (item.collectionStatus() != Evidence.CollectionStatus.OBSERVED) continue;
            if (item.freshness() != Evidence.Freshness.CURRENT) continue;
            if (item.type() != EvidenceType.SERVICE_STATUS) continue;
            if (!item.scope().contains("order-api")) continue;
            var containers = item.fact().path("data").path("containers");
            if (containers.isArray() && containers.size() > 0) {
                String st = containers.get(0).path("State").asText("");
                return st.contains("exited") || st.contains("stopped") || st.contains("down") || st.contains("unhealthy");
            }
        }
        return false;
    }
    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new RemoteDiscoveryMain.ConfigException("missing env: " + name);
        return v;
    }
}
