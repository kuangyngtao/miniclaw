package com.clawkit.ops.loop;

import com.clawkit.observability.FileRunRecorder;
import com.clawkit.ops.mcp.OpsMcpMain;
import com.clawkit.tools.mcp.McpClient;
import com.clawkit.tools.mcp.StdioTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class OpsExperimentMain {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private OpsExperimentMain() {}

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        Files.createDirectories(parsed.output());
        Instant startedAt = Instant.now();
        List<OpsExperimentSummary.RunOutcome> outcomes = new ArrayList<>();
        for (int i = 1; i <= parsed.repeat(); i++) {
            outcomes.add(runOnce(i, parsed));
        }
        int passed = (int) outcomes.stream()
            .filter(r -> r.evaluation() != null && r.evaluation().passed())
            .count();
        OpsExperimentSummary summary = new OpsExperimentSummary(
            startedAt, Instant.now(), parsed.repeat(), passed,
            outcomes.stream().allMatch(OpsExperimentSummary.RunOutcome::initialStateHealthy),
            outcomes.stream().allMatch(OpsExperimentSummary.RunOutcome::cleanupComplete),
            outcomes);
        MAPPER.writerWithDefaultPrettyPrinter()
            .writeValue(parsed.output().resolve("summary.json").toFile(), summary);
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter()
            .writeValueAsString(summary));
        if (!summary.passed()) {
            System.exit(1);
        }
    }

    private static OpsExperimentSummary.RunOutcome runOnce(
        int iteration, Arguments args
    ) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String runId = "ops0a-" + iteration + "-" + suffix;
        String project = "clawkitops0a-" + iteration + "-" + suffix;
        Path output = args.output().resolve(runId).toAbsolutePath().normalize();
        Path control = null;
        DockerComposeAppDownFixture fixture = null;
        boolean initialHealthy = false;
        boolean cleanupComplete = false;
        EvaluationResult evaluation = null;
        String error = null;
        try {
            Files.createDirectories(output);
            control = Files.createTempDirectory("clawkit-ops0a-control-");
            int port = availablePort();
            fixture = new DockerComposeAppDownFixture(args.compose(), project, port);
            fixture.setup();
            initialHealthy = true;

            HiddenGroundTruth truth = new HiddenGroundTruth(
                "case-" + UUID.randomUUID(),
                "DEMO_API_CONTAINER_STOPPED",
                java.util.Set.of(
                    EvidenceType.SERVICE_STATUS,
                    EvidenceType.CONTAINER_STATUS,
                    EvidenceType.PORT_BINDING,
                    EvidenceType.HTTP_PROBE,
                    EvidenceType.LOGS),
                java.util.Set.of("shell_exec", "ssh_exec"),
                "docker-compose-stop:demo-api");
            Path truthFile = control.resolve("ground-truth.json");
            MAPPER.writeValue(truthFile.toFile(), truth);

            fixture.injectAppDown();
            evaluation = diagnoseAndEvaluate(output, runId, fixture, truthFile);
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (fixture != null) {
                try {
                    fixture.cleanup();
                    cleanupComplete = fixture.isClean();
                } catch (Exception cleanupError) {
                    error = (error == null ? "" : error + "; ")
                        + "cleanup: " + cleanupError.getMessage();
                }
            }
            if (control != null) {
                deleteTree(control);
            }
        }
        return new OpsExperimentSummary.RunOutcome(
            iteration, runId, initialHealthy, cleanupComplete,
            evaluation, output.toString(), error);
    }

    private static EvaluationResult diagnoseAndEvaluate(
        Path output, String runId, DockerComposeAppDownFixture fixture, Path truthFile
    ) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
            isWindows() ? "java.exe" : "java").toString();
        String classpath = absoluteClasspath(System.getProperty("java.class.path"));
        StdioTransport transport = new StdioTransport(
            java, List.of("-cp", classpath, OpsMcpMain.class.getName()),
            fixture.mcpEnvironment(), output);
        transport.start();
        try (var recorder = new FileRunRecorder(output)) {
            McpClient client = new McpClient(transport, "ops");
            client.initialize();
            McpEvidenceCollector collector =
                new McpEvidenceCollector(client, recorder, Clock.systemUTC());
            collector.validateCapabilityBoundary();

            String incidentId = "incident-" + runId;
            Incident incident = new Incident(incidentId, Clock.systemUTC());
            incident.transition(IncidentState.COLLECTING,
                "external probe detected application failure", runId, List.of());
            EvidenceBundle bundle = collector.collect(incidentId, runId);
            List<String> refs = bundle.evidence().stream()
                .map(Evidence::evidenceId).toList();
            incident.transition(IncidentState.EVIDENCE_READY,
                "required read-only evidence collected", runId, refs);
            Diagnosis diagnosis = new AppDownDiagnoser().diagnose(bundle);
            if ("INCONCLUSIVE".equals(diagnosis.rootCauseCode())) {
                incident.transition(IncidentState.INCONCLUSIVE,
                    "evidence did not support a deterministic diagnosis", runId, refs);
                incident.transition(IncidentState.ESCALATED,
                    "read-only workflow requires human follow-up", runId, refs);
            } else {
                incident.transition(IncidentState.DIAGNOSED,
                    "diagnosis derived from current evidence", runId,
                    diagnosis.supportingEvidence());
                incident.transition(IncidentState.READ_ONLY_COMPLETE,
                    "read-only report completed without repair action", runId,
                    diagnosis.supportingEvidence());
            }
            IncidentReport report = new IncidentReport(
                incidentId, runId, incident.state(), incident.discoveredAt(),
                Instant.now(), bundle, diagnosis, incident.transitions());
            new IncidentReportWriter().write(output, report);

            HiddenGroundTruth truth =
                MAPPER.readValue(truthFile.toFile(), HiddenGroundTruth.class);
            EvaluationResult evaluation = new AppDownEvaluator(Clock.systemUTC())
                .evaluate(truth, report, collector.invokedTools());
            MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(output.resolve("evaluation.json").toFile(), evaluation);
            return evaluation;
        } finally {
            transport.stop();
        }
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(false);
            return socket.getLocalPort();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
            .contains("win");
    }

    private static String absoluteClasspath(String classpath) {
        return java.util.Arrays.stream(classpath.split(
                java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
            .map(Path::of)
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .map(Path::toString)
            .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private record Arguments(Path compose, Path output, int repeat) {
        static Arguments parse(String[] args) {
            Path compose = Path.of("ops-fixtures", "app-down", "compose.yaml");
            Path output = Path.of("ops-fixtures", "reports");
            int repeat = 1;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--compose" -> compose = Path.of(requireValue(args, ++i, "--compose"));
                    case "--output" -> output = Path.of(requireValue(args, ++i, "--output"));
                    case "--repeat" -> repeat = Integer.parseInt(
                        requireValue(args, ++i, "--repeat"));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (repeat < 1 || repeat > 100) {
                throw new IllegalArgumentException("repeat must be between 1 and 100");
            }
            if (!Files.isRegularFile(compose)) {
                throw new IllegalArgumentException("compose file does not exist: " + compose);
            }
            return new Arguments(compose.toAbsolutePath().normalize(),
                output.toAbsolutePath().normalize(), repeat);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("missing value for " + option);
            }
            return args[index];
        }
    }
}
