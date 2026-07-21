package com.clawkit.ops.loop;

import com.clawkit.ops.mcp.CommandExecutor;
import com.clawkit.ops.mcp.CommandResult;
import com.clawkit.ops.mcp.ProcessCommandExecutor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DockerComposeAppDownFixture {
    private final Path composeFile;
    private final String projectName;
    private final int hostPort;
    private final CommandExecutor commands;
    private final Map<String, String> environment;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();

    public DockerComposeAppDownFixture(Path composeFile, String projectName, int hostPort) {
        this(composeFile, projectName, hostPort, new ProcessCommandExecutor());
    }

    DockerComposeAppDownFixture(
        Path composeFile, String projectName, int hostPort, CommandExecutor commands
    ) {
        this.composeFile = composeFile.toAbsolutePath().normalize();
        this.projectName = projectName;
        this.hostPort = hostPort;
        this.commands = commands;
        this.environment = Map.of("OPS_HTTP_PORT", Integer.toString(hostPort));
    }

    public void setup() {
        cleanup();
        requireSuccess(run(Duration.ofMinutes(2),
            "up", "--detach", "--wait", "--wait-timeout", "60"),
            "failed to establish App Down fixture");
        int status = awaitStatus(code -> code == 200, Duration.ofSeconds(20));
        if (status != 200) {
            throw new IllegalStateException(
                "fixture did not reach healthy external HTTP state; last status=" + status);
        }
    }

    public void injectAppDown() {
        requireSuccess(run(Duration.ofSeconds(20),
            "stop", "--timeout", "5", "demo-api"),
            "failed to inject demo-api container stop");
        int status = awaitStatus(code -> code >= 500, Duration.ofSeconds(20));
        if (status < 500) {
            throw new IllegalStateException(
                "fault was not externally observable; last status=" + status);
        }
    }

    public void cleanup() {
        CommandResult result = run(Duration.ofSeconds(30),
            "down", "--volumes", "--remove-orphans", "--timeout", "10");
        if (!result.success() && !result.stderr().contains("not found")) {
            throw new IllegalStateException("fixture cleanup failed: " + detail(result));
        }
    }

    public boolean isClean() {
        CommandResult result = run(Duration.ofSeconds(10), "ps", "--all", "-q");
        return result.success() && result.stdout().isBlank();
    }

    public URI healthUri() {
        return URI.create("http://127.0.0.1:" + hostPort + "/health");
    }

    public Map<String, String> mcpEnvironment() {
        return Map.of(
            "CLAWKIT_OPS_COMPOSE_FILE", composeFile.toString(),
            "CLAWKIT_OPS_PROJECT", projectName,
            "CLAWKIT_OPS_SERVICES", "gateway,demo-api",
            "CLAWKIT_OPS_PORTS", "gateway:80,demo-api:80",
            "CLAWKIT_OPS_ENDPOINTS", "gateway-health=" + healthUri(),
            "OPS_HTTP_PORT", Integer.toString(hostPort));
    }

    private int awaitStatus(
        java.util.function.IntPredicate expected, Duration timeout
    ) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int last = -1;
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(healthUri())
                    .timeout(Duration.ofSeconds(2)).GET().build();
                last = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (expected.test(last)) return last;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            } catch (Exception ignored) {
                last = -1;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    private CommandResult run(Duration timeout, String... args) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("compose");
        command.add("--ansi");
        command.add("never");
        command.add("-f");
        command.add(composeFile.toString());
        command.add("-p");
        command.add(projectName);
        command.addAll(List.of(args));
        return commands.execute(command, environment, timeout, 65_536);
    }

    private static void requireSuccess(CommandResult result, String message) {
        if (!result.success()) {
            throw new IllegalStateException(message + ": " + detail(result));
        }
    }

    private static String detail(CommandResult result) {
        return result.stderr().isBlank() ? result.stdout() : result.stderr();
    }
}
