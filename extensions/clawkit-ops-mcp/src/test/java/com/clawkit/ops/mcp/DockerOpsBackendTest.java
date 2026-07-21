package com.clawkit.ops.mcp;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerOpsBackendTest {

    @Test
    void serviceStatusUsesFixedDockerComposeArguments() {
        var commands = new CapturingCommands(List.of(
            new CommandResult(0,
                "[{\"Service\":\"gateway\",\"State\":\"running\"}]",
                "", false, false, 48)));
        var backend = backend(commands);

        OpsToolResult result = backend.serviceStatus("gateway");

        assertThat(result.success()).isTrue();
        assertThat(result.data().path("containers").get(0)
            .path("State").asText()).isEqualTo("running");
        assertThat(commands.commands.getFirst()).containsExactly(
            "docker", "compose", "--ansi", "never",
            "-f", Path.of("compose.yaml").toAbsolutePath().normalize().toString(),
            "-p", "ops0a-test", "ps", "--all", "--format", "json", "gateway");
    }

    @Test
    void logsResolveAllowlistedContainerThenUseAbsoluteBoundedWindow() {
        var commands = new CapturingCommands(List.of(
            new CommandResult(0, "abc123\n", "", false, false, 7),
            new CommandResult(0, "2026-07-20T00:00:00Z error\n",
                "", false, false, 31)));
        var backend = backend(commands);

        OpsToolResult result = backend.logs("demo-api", Duration.ofMinutes(5), 20);

        assertThat(result.success()).isTrue();
        assertThat(commands.commands.get(1)).containsExactly(
            "docker", "container", "logs", "--timestamps",
            "--since", "2026-07-19T23:55:00Z",
            "--until", "2026-07-20T00:00:00Z",
            "--tail", "20", "abc123");
    }

    @Test
    void rejectsNonAllowlistedTargetsBeforeAnyCommand() {
        var commands = new CapturingCommands(List.of());
        var backend = backend(commands);

        assertThatThrownBy(() -> backend.serviceStatus("secret-db"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not allowlisted");
        assertThat(commands.commands).isEmpty();
    }

    @Test
    void rejectsOversizedLogWindowBeforeAnyCommand() {
        var commands = new CapturingCommands(List.of());
        var backend = backend(commands);

        assertThatThrownBy(() ->
            backend.logs("gateway", Duration.ofMinutes(16), 20))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("window");
        assertThat(commands.commands).isEmpty();
    }

    private static DockerOpsBackend backend(CommandExecutor commands) {
        OpsTargetConfig config = new OpsTargetConfig(
            Path.of("compose.yaml"), "ops0a-test",
            Set.of("gateway", "demo-api"),
            Map.of("gateway", Set.of(80), "demo-api", Set.of(80)),
            Map.of("gateway-health", URI.create("http://127.0.0.1:18080/health")),
            Duration.ofSeconds(10), Duration.ofMinutes(15), 32_768, 200);
        return new DockerOpsBackend(config, commands, HttpClient.newHttpClient(),
            Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC),
            Map.of());
    }

    private static final class CapturingCommands implements CommandExecutor {
        private final List<CommandResult> results;
        private final List<List<String>> commands = new ArrayList<>();
        private int index;

        private CapturingCommands(List<CommandResult> results) {
            this.results = results;
        }

        @Override
        public CommandResult execute(
            List<String> command, Map<String, String> environment,
            Duration timeout, int maxOutputBytes
        ) {
            commands.add(List.copyOf(command));
            return results.get(index++);
        }
    }
}
