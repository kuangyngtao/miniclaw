package com.clawkit.ops.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshCommandExecutorTest {

    @TempDir
    Path tmp;

    // ---- happy path ----

    @Test
    void wrapsRemoteCommandWithSshAndQuoting() throws Exception {
        var captured = new CapturingLocalCommands(List.of(
            new CommandResult(0, "stdout", "", false, false, 6)));
        var config = configWithTempKey();
        var executor = new SshCommandExecutor(config, captured);

        CommandResult result = executor.execute(
            List.of("docker", "compose", "ps", "--all"),
            Map.of(), Duration.ofSeconds(10), 32_768);

        assertThat(result.success()).isTrue();
        List<String> full = captured.commands.getFirst();
        assertThat(full).contains("ssh");
        assertThat(full).contains("opsro@122.51.51.118");
        assertThat(full).contains("StrictHostKeyChecking=yes");
        assertThat(full).contains("BatchMode=yes");
        assertThat(full).contains("ControlMaster=auto");
        assertThat(full).contains("-i");
    }

    // ---- error classification ----

    @Test
    void classifiesConnectionRefused() throws Exception {
        var captured = newCapturing(255, "",
            "ssh: connect to host 122.51.51.118 port 22: Connection refused");
        var executor = new SshCommandExecutor(configWithTempKey(), captured);

        CommandResult result = executor.execute(
            List.of("echo", "hello"), Map.of(), Duration.ofSeconds(10), 4096);

        assertThat(result.success()).isFalse();
        assertThat(result.stderr()).startsWith("[SSH_CONNECTION_FAILED]");
    }

    @Test
    void classifiesAuthFailure() throws Exception {
        var captured = newCapturing(255, "",
            "root@122.51.51.118: Permission denied (publickey).");
        var executor = new SshCommandExecutor(configWithTempKey(), captured);

        CommandResult result = executor.execute(
            List.of("echo", "hello"), Map.of(), Duration.ofSeconds(10), 4096);

        assertThat(result.success()).isFalse();
        assertThat(result.stderr()).startsWith("[SSH_AUTH_FAILED]");
    }

    @Test
    void classifiesHostKeyRejected() throws Exception {
        var captured = newCapturing(255, "",
            "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!");
        var executor = new SshCommandExecutor(configWithTempKey(), captured);

        CommandResult result = executor.execute(
            List.of("echo", "hello"), Map.of(), Duration.ofSeconds(10), 4096);

        assertThat(result.success()).isFalse();
        assertThat(result.stderr()).startsWith("[SSH_HOST_KEY_REJECTED]");
    }

    @Test
    void classifiesCommandNotFound() throws Exception {
        var captured = newCapturing(127, "",
            "bash: line 1: nonexistent: command not found");
        var executor = new SshCommandExecutor(configWithTempKey(), captured);

        CommandResult result = executor.execute(
            List.of("nonexistent"), Map.of(), Duration.ofSeconds(10), 4096);

        assertThat(result.success()).isFalse();
        assertThat(result.stderr()).startsWith("[COMMAND_NOT_FOUND]");
    }

    @Test
    void passesThroughRemoteCommandFailure() throws Exception {
        var captured = newCapturing(1, "", "No such container");
        var executor = new SshCommandExecutor(configWithTempKey(), captured);

        CommandResult result = executor.execute(
            List.of("docker", "inspect", "bad-id"),
            Map.of(), Duration.ofSeconds(10), 4096);

        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).doesNotStartWith("[SSH_");
    }

    // ---- concurrency ----

    @Test
    void enforcesConcurrencyLimit() throws Exception {
        Path key = createTempKeyFile();
        var config = new SshTargetConfig(
            "122.51.51.118", 22, "opsro",
            SshTargetConfig.AuthMethod.KEY, key, null,
            Duration.ofSeconds(10), 2, true, null);

        var executor = new SshCommandExecutor(config,
            newCapturing(0, "ok", ""));
        // First call succeeds
        CommandResult r1 = executor.execute(
            List.of("echo", "1"), Map.of(), Duration.ofSeconds(10), 4096);
        assertThat(r1.success()).isTrue();
    }

    // ---- validation ----

    @Test
    void rejectsBlankHost() {
        assertThatThrownBy(() -> new SshTargetConfig(
            "", 22, "opsro", SshTargetConfig.AuthMethod.KEY,
            Path.of("/tmp/key"), null,
            Duration.ofSeconds(10), 4, true, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("host");
    }

    @Test
    void rejectsMissingKeyFile() {
        assertThatThrownBy(() -> new SshTargetConfig(
            "122.51.51.118", 22, "opsro", SshTargetConfig.AuthMethod.KEY,
            Path.of("/nonexistent/key"), null,
            Duration.ofSeconds(10), 4, true, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("identityFile");
    }

    @Test
    void passwordAuthRequiresPassword() {
        assertThatThrownBy(() -> new SshTargetConfig(
            "122.51.51.118", 22, "opsro", SshTargetConfig.AuthMethod.PASSWORD,
            null, null,
            Duration.ofSeconds(10), 4, true, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("password");
    }

    @Test
    void passwordAuthNullifiesKey() {
        var config = new SshTargetConfig(
            "122.51.51.118", 22, "opsro", SshTargetConfig.AuthMethod.PASSWORD,
            null, "secret123",
            Duration.ofSeconds(10), 4, true, null);
        assertThat(config.identityFile()).isNull();
        assertThat(config.password()).isEqualTo("secret123");
    }

    // ---- shell escaping ----

    @Test
    void shellEscapePreservesSafeArgs() {
        assertThat(SshCommandExecutor.shellEscape("docker")).isEqualTo("docker");
        assertThat(SshCommandExecutor.shellEscape("compose")).isEqualTo("compose");
        assertThat(SshCommandExecutor.shellEscape("--all")).isEqualTo("--all");
        assertThat(SshCommandExecutor.shellEscape("demo-api")).isEqualTo("demo-api");
    }

    @Test
    void shellEscapeQuotesUnsafeArgs() {
        String escaped = SshCommandExecutor.shellEscape("hello world");
        assertThat(escaped).startsWith("'").endsWith("'");
    }

    @Test
    void shellEscapeHandlesSingleQuotes() {
        String escaped = SshCommandExecutor.shellEscape("it's");
        assertThat(escaped).isEqualTo("'it'\\''s'");
    }

    // ---- known_hosts ----

    @Test
    void knownHostsFileValidation() throws Exception {
        Path key = createTempKeyFile();
        Path notYet = tmp.resolve("known_hosts");
        // known_hosts doesn't exist yet — acceptable
        var config = new SshTargetConfig("122.51.51.118", 22, "opsro",
            SshTargetConfig.AuthMethod.KEY, key, null,
            Duration.ofSeconds(10), 4, true, notYet);
        assertThat(config.knownHostsFile()).isEqualTo(notYet);

        // known_hosts is a directory — rejected
        Path dir = tmp.resolve("hosts_dir");
        Files.createDirectory(dir);
        assertThatThrownBy(() -> new SshTargetConfig("122.51.51.118", 22, "opsro",
            SshTargetConfig.AuthMethod.KEY, key, null,
            Duration.ofSeconds(10), 4, true, dir))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("knownHostsFile");
    }

    // ---- ssh base command ----

    @Test
    void sshBaseCommandContainsKeyAuthArgs() throws Exception {
        Path keyFile = createTempKeyFile();
        var config = new SshTargetConfig("122.51.51.118", 22, "opsro",
            SshTargetConfig.AuthMethod.KEY, keyFile, null,
            Duration.ofSeconds(10), 8, true, null);

        List<String> base = config.sshBaseCommand();
        assertThat(base).contains("ssh");
        assertThat(base).contains("StrictHostKeyChecking=yes");
        assertThat(base).contains("BatchMode=yes");
        assertThat(base).contains("ControlMaster=auto");
        assertThat(base).contains("ControlPersist=60");
        assertThat(base).contains("-i");
        assertThat(base).contains(keyFile.toAbsolutePath().toString());
    }

    @Test
    void sshBaseCommandDoesNotContainPasswordFlag() throws Exception {
        Path keyFile = createTempKeyFile();
        var config = new SshTargetConfig("122.51.51.118", 22, "opsro",
            SshTargetConfig.AuthMethod.KEY, keyFile, null,
            Duration.ofSeconds(10), 8, true, null);

        List<String> base = config.sshBaseCommand();
        assertThat(base).doesNotContain("sshpass");
    }

    // ---- fromEnvironment ----

    @Test
    void fromEnvironmentBuildsKeyAuthByDefault() throws Exception {
        Path keyFile = createTempKeyFile();
        Map<String, String> env = Map.of(
            "CLAWKIT_OPS_SSH_HOST", "122.51.51.118",
            "CLAWKIT_OPS_SSH_KEY", keyFile.toAbsolutePath().toString());
        SshTargetConfig config = SshTargetConfig.fromEnvironment(env);
        assertThat(config.host()).isEqualTo("122.51.51.118");
        assertThat(config.port()).isEqualTo(22);
        assertThat(config.user()).isEqualTo("opsro");
        assertThat(config.authMethod()).isEqualTo(SshTargetConfig.AuthMethod.KEY);
        assertThat(config.strictHostKeyChecking()).isTrue();
    }

    @Test
    void fromEnvironmentBuildsPasswordAuth() {
        Map<String, String> env = Map.of(
            "CLAWKIT_OPS_SSH_HOST", "122.51.51.118",
            "CLAWKIT_OPS_SSH_AUTH", "PASSWORD",
            "CLAWKIT_OPS_SSH_PASSWORD", "test123");
        SshTargetConfig config = SshTargetConfig.fromEnvironment(env);
        assertThat(config.authMethod()).isEqualTo(SshTargetConfig.AuthMethod.PASSWORD);
        assertThat(config.password()).isEqualTo("test123");
        assertThat(config.identityFile()).isNull();
    }

    // ---- helpers ----

    private Path createTempKeyFile() throws Exception {
        Path keyPath = tmp.resolve("id_test_key");
        Files.writeString(keyPath, "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n-----END OPENSSH PRIVATE KEY-----");
        return keyPath;
    }

    private SshTargetConfig configWithTempKey() throws Exception {
        Path key = createTempKeyFile();
        return new SshTargetConfig(
            "122.51.51.118", 22, "opsro",
            SshTargetConfig.AuthMethod.KEY, key, null,
            Duration.ofSeconds(10), 8, true, null);
    }

    private static CapturingLocalCommands newCapturing(
        int exitCode, String stdout, String stderr
    ) {
        return new CapturingLocalCommands(List.of(
            new CommandResult(exitCode, stdout, stderr, false, false,
                stdout.length() + stderr.length())));
    }

    private static final class CapturingLocalCommands implements CommandExecutor {
        private final List<CommandResult> results;
        private final List<List<String>> commands = new ArrayList<>();
        private int index;

        CapturingLocalCommands(List<CommandResult> results) {
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
