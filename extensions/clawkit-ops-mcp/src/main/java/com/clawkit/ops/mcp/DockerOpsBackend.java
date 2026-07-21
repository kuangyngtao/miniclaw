package com.clawkit.ops.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DockerOpsBackend implements OpsBackend {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpsTargetConfig config;
    private final CommandExecutor commands;
    private final HttpClient http;
    private final Clock clock;
    private final Map<String, String> environment;

    public DockerOpsBackend(OpsTargetConfig config) {
        this(config, new ProcessCommandExecutor(),
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
            Clock.systemUTC(), Map.of());
    }

    DockerOpsBackend(OpsTargetConfig config, CommandExecutor commands,
                     HttpClient http, Clock clock, Map<String, String> environment) {
        this.config = config;
        this.commands = commands;
        this.http = http;
        this.clock = clock;
        this.environment = Map.copyOf(environment);
    }

    @Override
    public OpsToolResult serviceStatus(String service) {
        config.requireService(service);
        List<String> command = compose("ps", "--all", "--format", "json", service);
        return commandResult("service_status", service, command, output -> {
            JsonNode parsed = parsePossiblyStreamingJson(output);
            ObjectNode data = MAPPER.createObjectNode();
            data.put("service", service);
            ArrayNode containers = data.putArray("containers");
            for (JsonNode item : parsed) {
                ObjectNode shaped = MAPPER.createObjectNode();
                copyText(item, shaped, "Service");
                copyText(item, shaped, "State");
                copyText(item, shaped, "Status");
                copyText(item, shaped, "Health");
                copyInt(item, shaped, "ExitCode");
                JsonNode publishers = item.path("Publishers");
                if (publishers.isArray()) {
                    ArrayNode shapedPublishers = shaped.putArray("publishers");
                    for (JsonNode publisher : publishers) {
                        ObjectNode binding = MAPPER.createObjectNode();
                        copyText(publisher, binding, "URL");
                        copyInt(publisher, binding, "TargetPort");
                        copyInt(publisher, binding, "PublishedPort");
                        copyText(publisher, binding, "Protocol");
                        shapedPublishers.add(binding);
                    }
                }
                containers.add(shaped);
            }
            return data;
        });
    }

    @Override
    public OpsToolResult containerStatus(String service) {
        config.requireService(service);
        Instant started = clock.instant();
        long startNanos = System.nanoTime();
        CommandResult idResult = commands.execute(
            compose("ps", "--all", "-q", service), environment,
            config.commandTimeout(), config.maxOutputBytes());
        if (!idResult.success()) {
            return failed("container_status", service, started, startNanos,
                idResult, "DOCKER_PS_FAILED");
        }
        String containerId = idResult.stdout().lines().findFirst().orElse("").trim();
        if (containerId.isBlank()) {
            return failed("container_status", service, started, startNanos,
                idResult, "CONTAINER_NOT_FOUND");
        }

        List<String> inspect = List.of("docker", "container", "inspect",
            "--format", "{{json .State}}", containerId);
        return commandResult("container_status", service, inspect, output -> {
            ObjectNode data = MAPPER.createObjectNode();
            data.put("service", service);
            data.put("containerId", shortId(containerId));
            JsonNode rawState = MAPPER.readTree(output.trim());
            ObjectNode state = data.putObject("state");
            copyText(rawState, state, "Status");
            copyBool(rawState, state, "Running");
            copyBool(rawState, state, "Paused");
            copyBool(rawState, state, "Restarting");
            copyBool(rawState, state, "OOMKilled");
            copyBool(rawState, state, "Dead");
            copyInt(rawState, state, "ExitCode");
            copyText(rawState, state, "Error");
            copyText(rawState, state, "StartedAt");
            copyText(rawState, state, "FinishedAt");
            if (rawState.path("Health").isObject()) {
                ObjectNode health = state.putObject("Health");
                copyText(rawState.path("Health"), health, "Status");
                copyInt(rawState.path("Health"), health, "FailingStreak");
            }
            return data;
        });
    }

    @Override
    public OpsToolResult ports(String service, int containerPort) {
        config.requirePort(service, containerPort);
        return commandResult("ports", service + ":" + containerPort,
            compose("port", service, Integer.toString(containerPort)), output -> {
                ObjectNode data = MAPPER.createObjectNode();
                data.put("service", service);
                data.put("containerPort", containerPort);
                ArrayNode bindings = data.putArray("bindings");
                output.lines().map(String::trim).filter(s -> !s.isBlank())
                    .forEach(bindings::add);
                return data;
            });
    }

    @Override
    public OpsToolResult httpProbe(String endpoint) {
        var uri = config.endpoint(endpoint);
        Instant observedAt = clock.instant();
        long startNanos = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(config.commandTimeout())
                .header("Accept", "application/json,text/plain,*/*")
                .GET()
                .build();
            HttpResponse<byte[]> response = http.send(
                request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body() != null ? response.body() : new byte[0];
            int retained = Math.min(body.length, config.maxOutputBytes());
            String text = new String(body, 0, retained, StandardCharsets.UTF_8);
            ObjectNode data = MAPPER.createObjectNode();
            data.put("endpoint", endpoint);
            data.put("uri", uri.toString());
            data.put("statusCode", response.statusCode());
            data.put("healthy", response.statusCode() >= 200 && response.statusCode() < 400);
            data.put("body", text);
            data.put("latencyMs", elapsedMillis(startNanos));
            return result("http_probe", endpoint, observedAt, true, data,
                null, null,
                "java-http-client", startNanos, body.length, retained,
                body.length > retained);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return exception("http_probe", endpoint, observedAt, startNanos,
                "PROBE_INTERRUPTED", e);
        } catch (Exception e) {
            return exception("http_probe", endpoint, observedAt, startNanos,
                "PROBE_FAILED", e);
        }
    }

    @Override
    public OpsToolResult logs(String service, Duration window, int tail) {
        config.requireService(service);
        if (window == null || window.isZero() || window.isNegative()
            || window.compareTo(config.maxLogWindow()) > 0) {
            throw new IllegalArgumentException(
                "window must be between 1 second and " + config.maxLogWindow().toSeconds() + " seconds");
        }
        if (tail < 1 || tail > config.maxLogLines()) {
            throw new IllegalArgumentException(
                "tail must be between 1 and " + config.maxLogLines());
        }

        CommandResult idResult = commands.execute(
            compose("ps", "--all", "-q", service), environment,
            config.commandTimeout(), config.maxOutputBytes());
        if (!idResult.success()) {
            return failed("logs", service, clock.instant(), System.nanoTime(),
                idResult, "DOCKER_PS_FAILED");
        }
        String containerId = idResult.stdout().lines().findFirst().orElse("").trim();
        if (containerId.isBlank()) {
            return failed("logs", service, clock.instant(), System.nanoTime(),
                idResult, "CONTAINER_NOT_FOUND");
        }

        Instant until = clock.instant();
        Instant since = until.minus(window);
        List<String> command = List.of(
            "docker", "container", "logs",
            "--timestamps",
            "--since", DateTimeFormatter.ISO_INSTANT.format(since),
            "--until", DateTimeFormatter.ISO_INSTANT.format(until),
            "--tail", Integer.toString(tail),
            containerId);
        Instant observedAt = clock.instant();
        long startNanos = System.nanoTime();
        CommandResult logs = commands.execute(
            command, environment, config.commandTimeout(), config.maxOutputBytes());
        if (!logs.success()) {
            return failed("logs", service, observedAt, startNanos, logs,
                logs.timedOut() ? "COMMAND_TIMEOUT" : "COMMAND_FAILED");
        }
        String combined = logs.stderr().isBlank()
            ? logs.stdout() : logs.stdout() + logs.stderr();
        byte[] combinedBytes = combined.getBytes(StandardCharsets.UTF_8);
        int retainedBytes = Math.min(combinedBytes.length, config.maxOutputBytes());
        String output = new String(combinedBytes, 0, retainedBytes, StandardCharsets.UTF_8);
        ObjectNode data = MAPPER.createObjectNode();
        data.put("service", service);
        data.put("containerId", shortId(containerId));
        data.put("since", since.toString());
        data.put("until", until.toString());
        data.put("tail", tail);
        data.put("text", output);
        return result("logs", service, observedAt, true, data, null, null,
            "docker-cli", startNanos, logs.totalOutputBytes(),
            retainedBytes, logs.truncated() || combinedBytes.length > retainedBytes);
    }

    private OpsToolResult commandResult(
        String tool, String target, List<String> command, OutputParser parser
    ) {
        Instant observedAt = clock.instant();
        long startNanos = System.nanoTime();
        CommandResult commandResult = commands.execute(
            command, environment, config.commandTimeout(), config.maxOutputBytes());
        if (!commandResult.success()) {
            return failed(tool, target, observedAt, startNanos,
                commandResult, commandResult.timedOut() ? "COMMAND_TIMEOUT" : "COMMAND_FAILED");
        }
        try {
            JsonNode data = parser.parse(commandResult.stdout());
            int returned = commandResult.stdout().getBytes(StandardCharsets.UTF_8).length;
            return result(tool, target, observedAt, true, data, null, null,
                "docker-cli", startNanos, commandResult.totalOutputBytes(),
                returned, commandResult.truncated());
        } catch (Exception e) {
            return exception(tool, target, observedAt, startNanos,
                "INVALID_DOCKER_OUTPUT", e);
        }
    }

    private OpsToolResult failed(
        String tool, String target, Instant observedAt, long startNanos,
        CommandResult command, String errorCode
    ) {
        String detail = command.stderr().isBlank() ? command.stdout() : command.stderr();
        int returned = detail.getBytes(StandardCharsets.UTF_8).length;
        return result(tool, target, observedAt, false, MAPPER.createObjectNode(),
            errorCode, detail, "docker-cli", startNanos, command.totalOutputBytes(),
            returned, command.truncated());
    }

    private OpsToolResult exception(
        String tool, String target, Instant observedAt, long startNanos,
        String errorCode, Exception error
    ) {
        return result(tool, target, observedAt, false, MAPPER.createObjectNode(),
            errorCode, error.getMessage(), "local", startNanos, 0, 0, false);
    }

    private OpsToolResult result(
        String tool, String target, Instant observedAt, boolean success,
        JsonNode data, String errorCode, String error, String backend,
        long startNanos, long totalBytes, int returnedBytes, boolean truncated
    ) {
        return new OpsToolResult(tool, target, observedAt, clock.instant(), true,
            success, data, errorCode, error,
            new OpsToolResult.Audit(backend, elapsedMillis(startNanos),
                Math.toIntExact(config.commandTimeout().toMillis()),
                totalBytes, returnedBytes, truncated));
    }

    private List<String> compose(String... args) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("compose");
        command.add("--ansi");
        command.add("never");
        command.add("-f");
        command.add(config.composeFile().toString());
        command.add("-p");
        command.add(config.projectName());
        command.addAll(List.of(args));
        return List.copyOf(command);
    }

    private static JsonNode parsePossiblyStreamingJson(String output) throws Exception {
        String trimmed = output.trim();
        if (trimmed.isBlank()) {
            return MAPPER.createArrayNode();
        }
        JsonNode parsed = MAPPER.readTree(trimmed);
        if (parsed.isArray()) {
            return parsed;
        }
        ArrayNode array = MAPPER.createArrayNode();
        if (parsed.isObject()) {
            array.add(parsed);
            return array;
        }
        for (String line : trimmed.lines().toList()) {
            if (!line.isBlank()) array.add(MAPPER.readTree(line));
        }
        return array;
    }

    private static long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static String shortId(String containerId) {
        return containerId.substring(0, Math.min(12, containerId.length()));
    }

    private static void copyText(JsonNode from, ObjectNode to, String field) {
        JsonNode value = from.get(field);
        if (value != null && !value.isNull()) to.put(field, value.asText());
    }

    private static void copyInt(JsonNode from, ObjectNode to, String field) {
        JsonNode value = from.get(field);
        if (value != null && value.isNumber()) to.put(field, value.longValue());
    }

    private static void copyBool(JsonNode from, ObjectNode to, String field) {
        JsonNode value = from.get(field);
        if (value != null && value.isBoolean()) to.put(field, value.booleanValue());
    }

    @FunctionalInterface
    private interface OutputParser {
        JsonNode parse(String output) throws Exception;
    }
}
