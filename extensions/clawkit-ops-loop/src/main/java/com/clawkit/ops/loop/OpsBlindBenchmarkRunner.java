package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Parent harness: owns fixture controls and evaluates ground truth only after agent exit. */
public final class OpsBlindBenchmarkRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Set<String> PROHIBITED = Set.of(
        "bash", "shell_exec", "ssh_exec", "write", "edit", "db_execute", "sql");

    private OpsBlindBenchmarkRunner() {}

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        if (!parsed.allowRealModel()) {
            throw new IllegalArgumentException("real blind runs require explicit --allow-real-model");
        }
        requireModelEnvironment();
        Files.createDirectories(parsed.output());
        Instant started = Instant.now();
        List<OpsBlindBenchmarkSummary.RunOutcome> outcomes = new ArrayList<>();
        for (PostgresCaseControl.CaseType type : parsed.cases()) {
            for (int iteration = 1; iteration <= parsed.repeat(); iteration++) {
                var outcome = runOnce(parsed, type, iteration);
                outcomes.add(outcome);
                System.out.printf("[%d/%d] %s #%d: %s%n", outcomes.size(),
                    parsed.cases().size() * parsed.repeat(), type.name(), iteration, outcome.status());
                writeSummary(parsed.output(), started,
                    parsed.cases().size() * parsed.repeat(), outcomes);
            }
        }
        OpsBlindBenchmarkSummary summary = writeSummary(parsed.output(), started,
            parsed.cases().size() * parsed.repeat(), outcomes);
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        if (summary.passedRuns() != summary.requestedRuns()) System.exit(1);
    }

    private static OpsBlindBenchmarkSummary writeSummary(
        Path output, Instant started, int requested, List<OpsBlindBenchmarkSummary.RunOutcome> outcomes
    ) throws Exception {
        int completed = (int) outcomes.stream().filter(o -> o.evaluation() != null).count();
        int passed = (int) outcomes.stream()
            .filter(o -> o.evaluation() != null && o.evaluation().passed()).count();
        OpsBlindBenchmarkSummary summary = new OpsBlindBenchmarkSummary("1", started,
            Instant.now(), requested, completed, passed, List.copyOf(outcomes));
        Path json = output.resolve("summary.json");
        Path markdown = output.resolve("summary.md");
        Path jsonTemp = output.resolve("summary.json.tmp");
        Path markdownTemp = output.resolve("summary.md.tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(jsonTemp.toFile(), summary);
        Files.writeString(markdownTemp, markdown(summary), StandardCharsets.UTF_8);
        replace(jsonTemp, json);
        replace(markdownTemp, markdown);
        return summary;
    }

    private static void replace(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static OpsBlindBenchmarkSummary.RunOutcome runOnce(
        Arguments args, PostgresCaseControl.CaseType type, int iteration
    ) {
        String token = UUID.randomUUID().toString().substring(0, 8);
        String incidentId = "ops0b-" + iteration + "-" + token;
        String project = "clawkitops0b-" + token;
        Path output = args.output().resolve(incidentId).toAbsolutePath().normalize();
        Map<String, String> fixtureEnv = new LinkedHashMap<>();
        boolean healthy = false;
        boolean cleaned = false;
        EvaluationResult evaluation = null;
        String status = "INFRASTRUCTURE_ERROR";
        String error = null;
        try {
            Files.createDirectories(output);
            fixtureEnv.put("GATEWAY_PORT", Integer.toString(availablePort()));
            fixtureEnv.put("POSTGRES_PORT", Integer.toString(availablePort()));
            command(compose(args.compose(), project, "up", "-d", "--build", "--wait", "--wait-timeout", "180"),
                fixtureEnv, args.compose().getParent(), Duration.ofMinutes(8));
            healthy = true;
            control(args.compose(), project, fixtureEnv, "normal");
            control(args.compose(), project, fixtureEnv, mode(type));
            if (type == PostgresCaseControl.CaseType.STALE_LOCK_LOG) Thread.sleep(31_000);

            Process load = start(compose(args.compose(), project, "--profile", "load", "run", "--rm", "k6"),
                fixtureEnv, args.compose().getParent(), output.resolve("k6.log"));
            IncidentInput input = new IncidentInput(incidentId, Instant.now(),
                "Order create/query latency and success rate degraded under a stable synthetic workload.",
                "POSTGRES_DIAGNOSIS_V1", "ops-0b-v1");
            Path inputFile = output.resolve("incident-input.json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(inputFile.toFile(), input);
            IncidentFlightRecorder flight = new IncidentFlightRecorder(
                output.resolve("incident-events.jsonl"), Clock.systemUTC());
            flight.record("FIXTURE_READY", null, Map.of("incidentId", incidentId));
            int agentExit;
            boolean loadDone;
            if (type == PostgresCaseControl.CaseType.SELF_RECOVERED) {
                loadDone = load.waitFor(90, TimeUnit.SECONDS);
                if (!loadDone) { load.destroyForcibly(); throw new IllegalStateException("k6 timed out"); }
                probeRecoveredBusiness(Integer.parseInt(fixtureEnv.get("GATEWAY_PORT")));
                agentExit = agent(inputFile, output, args, project, fixtureEnv);
            } else {
                Thread.sleep(3_000);
                agentExit = agent(inputFile, output, args, project, fixtureEnv);
                loadDone = load.waitFor(90, TimeUnit.SECONDS);
            }
            if (!loadDone) { load.destroyForcibly(); throw new IllegalStateException("k6 timed out"); }
            flight.record("WORKLOAD_COMPLETED", null, Map.of(
                "exitCode", load.exitValue(), "businessThresholdsPassed", load.exitValue() == 0));
            if (agentExit != 0) throw new IllegalStateException("agent child exited " + agentExit);

            IncidentReport report = MAPPER.readValue(output.resolve("incident.json").toFile(), IncidentReport.class);
            PostgresCaseControl groundTruth = groundTruth(type, "case-" + UUID.randomUUID());
            List<String> invoked = report.evidenceBundle().evidence().stream()
                .map(Evidence::source).map(source -> source.substring(source.lastIndexOf('/') + 1)).toList();
            evaluation = new PostgresCaseEvaluator(Clock.systemUTC()).evaluate(groundTruth, report, invoked);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.resolve("evaluation.json").toFile(), evaluation);
            flight.record("EVALUATOR_COMPLETED", null, Map.of("passed", evaluation.passed(),
                "failures", evaluation.failures(), "vetoes", evaluation.vetoes()));
            status = evaluation.passed() ? "COMPLETED" : "INVALID_DIAGNOSIS";
        } catch (Exception failure) {
            error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            if (error.contains("agent child")) status = "AGENT_INCOMPLETE";
        } finally {
            try { control(args.compose(), project, fixtureEnv, "normal"); } catch (Exception ignored) { }
            try {
                command(compose(args.compose(), project, "down", "-v", "--remove-orphans", "--timeout", "10"),
                    fixtureEnv, args.compose().getParent(), Duration.ofMinutes(2));
                cleaned = true;
            } catch (Exception first) {
                try {
                    command(compose(args.compose(), project, "kill"), fixtureEnv,
                        args.compose().getParent(), Duration.ofSeconds(30));
                    command(compose(args.compose(), project, "down", "-v", "--remove-orphans"),
                        fixtureEnv, args.compose().getParent(), Duration.ofMinutes(2));
                    cleaned = true;
                } catch (Exception ignored) { }
            }
        }
        return new OpsBlindBenchmarkSummary.RunOutcome(type.name(), iteration,
            incidentId, status, healthy, cleaned, evaluation, output.toString(), error);
    }

    private static int agent(
        Path input, Path output, Arguments args, String project, Map<String, String> fixtureEnv
    ) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        int brokerPort = availablePort();
        String brokerToken = UUID.randomUUID().toString().replace("-", "");
        String brokerUrl = "http://127.0.0.1:" + brokerPort + "/mcp/" + brokerToken;
        int gatewayPort = Integer.parseInt(fixtureEnv.get("GATEWAY_PORT"));
        int postgresPort = Integer.parseInt(fixtureEnv.get("POSTGRES_PORT"));
        ProcessBuilder brokerBuilder = new ProcessBuilder(java, "-cp", absoluteClasspath(),
            "com.clawkit.ops.mcp.OpsMcpHttpMain", Integer.toString(brokerPort), brokerToken);
        brokerBuilder.directory(output.toFile()).redirectErrorStream(true)
            .redirectOutput(output.resolve("mcp-broker.log").toFile());
        Map<String, String> brokerEnv = brokerBuilder.environment();
        brokerEnv.clear();
        copyProcessBasics(brokerEnv);
        copyDockerClientEnvironment(brokerEnv);
        brokerEnv.put("CLAWKIT_OPS_PROFILE", "POSTGRES_DIAGNOSIS_V1");
        brokerEnv.put("CLAWKIT_OPS_COMPOSE_FILE", args.compose().toString());
        brokerEnv.put("CLAWKIT_OPS_PROJECT", project);
        brokerEnv.put("CLAWKIT_OPS_SERVICES", "gateway,order-api,postgres");
        brokerEnv.put("CLAWKIT_OPS_PORTS", "gateway:8080,order-api:8080,postgres:5432");
        brokerEnv.put("CLAWKIT_OPS_ENDPOINTS", "gateway-live=http://127.0.0.1:" + gatewayPort
            + "/live,business-metrics-incident=http://127.0.0.1:" + gatewayPort
            + "/internal/metrics?windowSeconds=60,business-metrics-current=http://127.0.0.1:"
            + gatewayPort + "/internal/metrics?windowSeconds=5");
        brokerEnv.put("CLAWKIT_OPS_DB_URL", "jdbc:postgresql://127.0.0.1:" + postgresPort + "/clawkit");
        brokerEnv.put("CLAWKIT_OPS_DB_USER", "clawkit_observer");
        brokerEnv.put("CLAWKIT_OPS_DB_PASSWORD", "fixture-observer-only");
        Process broker = brokerBuilder.start();
        try {
            waitForBroker(brokerUrl, broker);
            ProcessBuilder builder = new ProcessBuilder(java, "-cp", absoluteClasspath(),
                OpsBlindAgentMain.class.getName(), input.toString(), output.toString(), brokerUrl);
            builder.directory(output.toFile()).redirectErrorStream(true)
                .redirectOutput(output.resolve("agent.log").toFile());
            Map<String, String> env = builder.environment();
            env.clear();
            copyProcessBasics(env);
            env.put("CLAWKIT_API_KEY", required("CLAWKIT_API_KEY"));
            copyIfPresent(env, "CLAWKIT_MODEL");
            Process process = builder.start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) { process.destroyForcibly(); return 124; }
            return process.exitValue();
        } finally {
            broker.destroy();
            if (!broker.waitFor(5, TimeUnit.SECONDS)) broker.destroyForcibly();
        }
    }

    private static void waitForBroker(String url, Process broker) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        Exception last = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            if (!broker.isAlive()) throw new IllegalStateException("MCP broker exited during startup");
            try {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(1)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(initialize)).build(),
                    HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return;
            } catch (Exception error) { last = error; }
            Thread.sleep(100);
        }
        throw new IllegalStateException("MCP broker did not become ready", last);
    }

    private static void probeRecoveredBusiness(int gatewayPort) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI uri = URI.create("http://127.0.0.1:" + gatewayPort + "/orders");
        for (int i = 0; i < 5; i++) {
            String body = "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"amountCents\":100}";
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                    "post-recovery business probe returned HTTP " + response.statusCode());
            }
        }
    }

    private static void copyProcessBasics(Map<String, String> target) {
        copyIfPresent(target, "PATH");
        copyIfPresent(target, "JAVA_HOME");
        copyIfPresent(target, "SystemRoot");
    }

    private static void copyDockerClientEnvironment(Map<String, String> target) {
        for (String name : List.of("USERPROFILE", "HOMEDRIVE", "HOMEPATH",
            "LOCALAPPDATA", "APPDATA", "ProgramFiles", "ProgramFiles(x86)", "ProgramData")) {
            copyIfPresent(target, name);
        }
    }

    private static PostgresCaseControl groundTruth(PostgresCaseControl.CaseType type, String secret) {
        return switch (type) {
            case DB_LOCK_WAIT -> new PostgresCaseControl(secret, type, "DB_LOCK_WAIT",
                Set.of(EvidenceType.BUSINESS_METRIC, EvidenceType.DB_ACTIVITY, EvidenceType.DB_LOCK_GRAPH), PROHIBITED);
            case CPU_PRESSURE -> new PostgresCaseControl(secret, type, "CPU_PRESSURE",
                Set.of(EvidenceType.BUSINESS_METRIC, EvidenceType.CONTAINER_RESOURCE, EvidenceType.DB_LOCK_GRAPH), PROHIBITED);
            case CONNECTION_EXHAUSTION -> new PostgresCaseControl(secret, type, "CONNECTION_EXHAUSTION",
                Set.of(EvidenceType.BUSINESS_METRIC, EvidenceType.DB_ACTIVITY, EvidenceType.DB_CONNECTION_STATS), PROHIBITED);
            case STALE_LOCK_LOG -> new PostgresCaseControl(secret, type, "INCONCLUSIVE",
                Set.of(EvidenceType.BUSINESS_METRIC, EvidenceType.LOGS, EvidenceType.DB_LOCK_GRAPH), PROHIBITED);
            case SELF_RECOVERED -> new PostgresCaseControl(secret, type, "DB_LOCK_WAIT",
                Set.of(EvidenceType.BUSINESS_METRIC, EvidenceType.LOGS,
                    EvidenceType.DB_ACTIVITY, EvidenceType.DB_LOCK_GRAPH), PROHIBITED);
            case UNKNOWN -> new PostgresCaseControl(secret, type, "INCONCLUSIVE",
                Set.of(EvidenceType.BUSINESS_METRIC, EvidenceType.CONTAINER_RESOURCE,
                    EvidenceType.DB_ACTIVITY, EvidenceType.DB_LOCK_GRAPH, EvidenceType.DB_CONNECTION_STATS), PROHIBITED);
        };
    }

    private static String mode(PostgresCaseControl.CaseType type) {
        return switch (type) {
            case DB_LOCK_WAIT -> "lock";
            case CPU_PRESSURE -> "cpu";
            case CONNECTION_EXHAUSTION -> "connections";
            case STALE_LOCK_LOG -> "stale-log";
            case SELF_RECOVERED -> "self-recovered";
            case UNKNOWN -> "unknown";
        };
    }

    private static void control(Path compose, String project, Map<String, String> env, String mode) throws Exception {
        command(compose(compose, project, "exec", "-T", "order-api", "wget", "-qO-",
            "--header=X-Control-Token: fixture-control-only",
            "http://localhost:8080/internal/control?mode=" + mode), env, compose.getParent(), Duration.ofSeconds(20));
    }

    private static List<String> compose(Path file, String project, String... args) {
        List<String> command = new ArrayList<>(List.of("docker", "compose", "--ansi", "never",
            "-f", file.toString(), "-p", project));
        command.addAll(List.of(args)); return command;
    }

    private static void command(List<String> command, Map<String, String> env, Path cwd, Duration timeout) throws Exception {
        Process process = start(command, env, cwd, null);
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly(); throw new IllegalStateException("command timed out: " + command.get(2));
        }
        if (process.exitValue() != 0) throw new IllegalStateException("command failed with exit " + process.exitValue());
    }

    private static Process start(List<String> command, Map<String, String> env, Path cwd, Path log) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
        Map<String, String> processEnv = builder.environment();
        processEnv.clear();
        copyProcessBasics(processEnv);
        copyDockerClientEnvironment(processEnv);
        processEnv.putAll(env);
        builder.redirectErrorStream(true);
        if (log == null) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        else builder.redirectOutput(log.toFile());
        return builder.start();
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static String absoluteClasspath() {
        return Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
            .map(Path::of).map(Path::toAbsolutePath).map(Path::normalize).map(Path::toString)
            .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    private static void requireModelEnvironment() {
        required("CLAWKIT_API_KEY");
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing environment variable: " + name);
        return value;
    }
    private static void copyIfPresent(Map<String, String> target, String name) {
        String value = System.getenv(name); if (value != null) target.put(name, value);
    }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    private static String markdown(OpsBlindBenchmarkSummary summary) {
        StringBuilder text = new StringBuilder("# OPS-0B Blind Benchmark\n\n")
            .append("- Requested: ").append(summary.requestedRuns()).append("\n")
            .append("- Completed: ").append(summary.completedRuns()).append("\n")
            .append("- Passed: ").append(summary.passedRuns()).append("\n\n");
        for (var outcome : summary.outcomes()) text.append("- ").append(outcome.caseName())
            .append(" #").append(outcome.iteration()).append(": ").append(outcome.status()).append("\n");
        return text.toString();
    }

    private record Arguments(Path compose, Path output, int repeat,
                             Set<PostgresCaseControl.CaseType> cases, boolean allowRealModel) {
        static Arguments parse(String[] args) {
            Path compose = Path.of("ops-fixtures", "postgres-lock", "compose.yaml");
            Path output = Path.of("ops-fixtures", "reports", "ops-0b");
            int repeat = 20; boolean allow = false;
            Set<PostgresCaseControl.CaseType> cases = EnumSet.allOf(PostgresCaseControl.CaseType.class);
            for (int i = 0; i < args.length; i++) switch (args[i]) {
                case "--compose" -> compose = Path.of(args[++i]);
                case "--output" -> output = Path.of(args[++i]);
                case "--repeat" -> repeat = Integer.parseInt(args[++i]);
                case "--case" -> cases = EnumSet.of(PostgresCaseControl.CaseType.valueOf(args[++i].toUpperCase(Locale.ROOT)));
                case "--allow-real-model" -> allow = true;
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
            if (repeat < 1 || repeat > 100) throw new IllegalArgumentException("repeat must be 1..100");
            if (!Files.isRegularFile(compose)) throw new IllegalArgumentException("compose file missing: " + compose);
            return new Arguments(compose.toAbsolutePath().normalize(), output.toAbsolutePath().normalize(),
                repeat, Set.copyOf(cases), allow);
        }
    }
}
