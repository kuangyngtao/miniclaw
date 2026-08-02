package com.clawkit.tools.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link RemoteSshConnectionSpec} implementations —
 * legacy explicit endpoint and OpenSSH alias modes.
 *
 * <p>PR-1 gate: exact argv, -F none, alias preservation, injection rejection.
 */
class RemoteSshConnectionSpecTest {

    @TempDir Path tempDir;

    // ── Legacy explicit endpoint ──────────────────────────────────────

    @Test
    void legacyShouldIncludeFNone() throws Exception {
        Path keyFile = tempDir.resolve("id_test");
        Path knownHosts = tempDir.resolve("known_hosts");
        Files.createFile(keyFile);
        Files.createFile(knownHosts);

        var spec = new RemoteEndpointConfig(
            "203.0.113.10", 22, "opsro",
            CredentialRef.parse("file:" + keyFile.toAbsolutePath()),
            knownHosts,
            Duration.ofSeconds(10), Duration.ofSeconds(15), 32768);

        List<String> args = spec.sshArgs();

        // Must contain -F none
        assertThat(args).contains("-F", "none");

        // Must contain safety overrides
        assertThat(args).contains("-o", "BatchMode=yes");
        assertThat(args).contains("-o", "PasswordAuthentication=no");
        assertThat(args).contains("-o", "StrictHostKeyChecking=yes");
        assertThat(args).contains("-T");
        assertThat(args).contains("-o", "ClearAllForwardings=yes");
        assertThat(args).contains("-o", "ForwardAgent=no");
        assertThat(args).contains("-o", "PermitLocalCommand=no");

        // Safety args must come before -F none
        int safetyIdx = args.indexOf("BatchMode=yes");
        int fNoneIdx = args.indexOf("-F");
        assertThat(safetyIdx).isLessThan(fNoneIdx);
    }

    @Test
    void legacyShouldNotAcceptPasswordOrKbdInteractive() {
        List<String> args = sampleLegacyArgs();
        assertThat(args).contains("-o", "PasswordAuthentication=no");
        assertThat(args).contains("-o", "KbdInteractiveAuthentication=no");
    }

    @Test
    void legacyShouldDisableTtyAndForwarding() {
        List<String> args = sampleLegacyArgs();
        assertThat(args).contains("-T");
        assertThat(args).contains("-o", "ClearAllForwardings=yes");
        assertThat(args).contains("-o", "ForwardAgent=no");
        assertThat(args).contains("-o", "ForwardX11=no");
    }

    @Test
    void legacySafeRefShouldNotContainKeyPath() throws Exception {
        Path keyFile = tempDir.resolve("id_test");
        Path knownHosts = tempDir.resolve("known_hosts");
        Files.createFile(keyFile);
        Files.createFile(knownHosts);

        var spec = new RemoteEndpointConfig(
            "203.0.113.10", 22, "opsro",
            CredentialRef.parse("file:" + keyFile.toAbsolutePath()),
            knownHosts,
            Duration.ofSeconds(10), Duration.ofSeconds(15), 32768);

        String ref = spec.safeRef();
        assertThat(ref).doesNotContain(".ssh");
        assertThat(ref).doesNotContain("id_");
        assertThat(ref).doesNotContain(keyFile.toString());
    }

    private List<String> sampleLegacyArgs() {
        try {
            Path keyFile = tempDir.resolve("id_test");
            Path knownHosts = tempDir.resolve("known_hosts");
            Files.createFile(keyFile);
            Files.createFile(knownHosts);
            return new RemoteEndpointConfig(
                "203.0.113.10", 22, "opsro",
                CredentialRef.parse("file:" + keyFile.toAbsolutePath()),
                knownHosts,
                Duration.ofSeconds(10), Duration.ofSeconds(15), 32768).sshArgs();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── OpenSSH alias ─────────────────────────────────────────────────

    @Test
    void aliasShouldPreserveAliasNotExpand() throws Exception {
        var spec = new OpenSshAliasConnectionSpec(
            "test-server", "opsro",
            Duration.ofSeconds(10), Duration.ofSeconds(15), 32768);

        List<String> args = spec.sshArgs();

        // The alias itself must be in the args
        assertThat(args).contains("test-server");

        // Must NOT contain -F none (alias mode needs config)
        assertThat(args).doesNotContain("-F", "none");

        // Must NOT contain -i (would break Agent/certificates)
        assertThat(args).doesNotContain("-i");

        // Must NOT contain IdentitiesOnly=yes
        assertThat(args).noneMatch(a -> a.contains("IdentitiesOnly=yes"));

        // Must contain -l opsro
        assertThat(args).contains("-l", "opsro");

        // Must contain safety overrides
        assertThat(args).contains("-o", "BatchMode=yes");
        assertThat(args).contains("-o", "StrictHostKeyChecking=yes");
        assertThat(args).contains("-T");
    }

    @Test
    void aliasSafeRefShouldNotContainHostOrKey() {
        var spec = new OpenSshAliasConnectionSpec(
            "test-server", "opsro",
            Duration.ofSeconds(10), Duration.ofSeconds(15), 32768);

        String ref = spec.safeRef();
        assertThat(ref).contains("alias:test-server");
        assertThat(ref).doesNotContain(".pem");
        assertThat(ref).doesNotContain(".ssh");
        assertThat(ref).doesNotContain("id_");
    }

    // ── Alias validation (injection rejection) ────────────────────────

    @Test
    void shouldRejectAliasWithLeadingDash() {
        assertThatThrownBy(() -> new OpenSshAliasConnectionSpec(
            "-oBatchMode=no", "opsro",
            Duration.ofSeconds(10), Duration.ofSeconds(15), 32768))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsafe");
    }

    @Test
    void shouldRejectAliasWithSpaces() {
        assertThatThrownBy(() -> new OpenSshAliasConnectionSpec(
            "my alias", "opsro",
            Duration.ofSeconds(10), Duration.ofSeconds(15), 32768))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsafe");
    }

    @Test
    void shouldRejectAliasWithNewlines() {
        assertThatThrownBy(() -> new OpenSshAliasConnectionSpec(
            "test\nserver", "opsro",
            Duration.ofSeconds(10), Duration.ofSeconds(15), 32768))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsafe");
    }

    @Test
    void shouldRejectAliasWithSemicolon() {
        assertThatThrownBy(() -> new OpenSshAliasConnectionSpec(
            "test;rm", "opsro",
            Duration.ofSeconds(10), Duration.ofSeconds(15), 32768))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsafe");
    }

    @Test
    void shouldRejectAliasWithBacktick() {
        assertThatThrownBy(() -> new OpenSshAliasConnectionSpec(
            "test`id`", "opsro",
            Duration.ofSeconds(10), Duration.ofSeconds(15), 32768))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsafe");
    }

    @Test
    void shouldAcceptValidAlias() {
        // Should not throw
        var spec = new OpenSshAliasConnectionSpec(
            "test-server.example.com", "opsro",
            Duration.ofSeconds(10), Duration.ofSeconds(15), 32768);
        assertThat(spec.alias()).isEqualTo("test-server.example.com");
    }

    @Test
    void shouldAcceptAliasWithUnderscore() {
        var spec = new OpenSshAliasConnectionSpec(
            "my_server_01", "opsro",
            Duration.ofSeconds(10), Duration.ofSeconds(15), 32768);
        assertThat(spec.alias()).isEqualTo("my_server_01");
    }

    // ── Safety policy ─────────────────────────────────────────────────

    @Test
    void safetyPolicyShouldIncludeAllRequiredParameters() {
        List<String> args = RemoteSshSafetyPolicy.safetyArgs();
        assertThat(args).contains("-T");
        assertThat(args).contains("-o", "BatchMode=yes");
        assertThat(args).contains("-o", "PasswordAuthentication=no");
        assertThat(args).contains("-o", "KbdInteractiveAuthentication=no");
        assertThat(args).contains("-o", "PreferredAuthentications=publickey");
        assertThat(args).contains("-o", "StrictHostKeyChecking=yes");
        assertThat(args).contains("-o", "ClearAllForwardings=yes");
        assertThat(args).contains("-o", "ForwardAgent=no");
        assertThat(args).contains("-o", "ForwardX11=no");
        assertThat(args).contains("-o", "PermitLocalCommand=no");
        assertThat(args).contains("-o", "ControlMaster=no");
        assertThat(args).contains("-o", "ControlPath=none");
        assertThat(args).contains("-o", "ControlPersist=no");
        assertThat(args).contains("-o", "Tunnel=no");
        assertThat(args).contains("-o", "AddKeysToAgent=no");
    }

    @Test
    void safetyArgsMustBeBeforeDestination() {
        var spec = new OpenSshAliasConnectionSpec(
            "test-server", "opsro",
            Duration.ofSeconds(10), Duration.ofSeconds(15), 32768);
        List<String> args = spec.sshArgs();

        int safetyIdx = args.indexOf("BatchMode=yes");
        int aliasIdx = args.indexOf("test-server");
        assertThat(safetyIdx).isLessThan(aliasIdx);
    }

    // ── No shell string concatenation ─────────────────────────────────

    @Test
    void allArgsMustBeListElements() {
        var spec = new OpenSshAliasConnectionSpec(
            "test-server", "opsro",
            Duration.ofSeconds(10), Duration.ofSeconds(15), 32768);
        List<String> args = spec.sshArgs();

        // No single element should contain spaces (shell concatenation)
        for (String arg : args) {
            // -o Name=Value is fine
            if (arg.startsWith("-o ")) {
                fail("Found -o with space: " + arg);
            }
        }
    }

    // ── Environment whitelist ─────────────────────────────────────────

    @Test
    void sshEnvAllowlistShouldContainAgentVariables() {
        var allowlist = RemoteMcpSession.SSH_ENV_ALLOWLIST;
        assertThat(allowlist).contains("SSH_AUTH_SOCK", "SSH_AGENT_PID",
            "PATH", "HOME", "TEMP", "TMP", "TMPDIR", "LANG");
    }

    @Test
    void sshEnvAllowlistShouldNotContainApiTokens() {
        var allowlist = RemoteMcpSession.SSH_ENV_ALLOWLIST;
        for (String key : allowlist) {
            assertThat(key.toUpperCase())
                .doesNotContain("API_KEY", "TOKEN", "SECRET", "WEBHOOK", "CREDENTIAL");
        }
    }

    @Test
    void sshEnvAllowlistShouldNotContainProviderEnv() {
        var allowlist = RemoteMcpSession.SSH_ENV_ALLOWLIST;
        for (String key : allowlist) {
            assertThat(key.toUpperCase())
                .doesNotContain("DEEPSEEK", "OPENAI", "ANTHROPIC", "CLAWKIT_API");
        }
    }

    // ── targetId validation ───────────────────────────────────────────

    @Test
    void shouldValidateTargetId() {
        RemoteSshSafetyPolicy.validateTargetId("test-server");
        RemoteSshSafetyPolicy.validateTargetId("my_server_01");
        // Should not throw
    }

    @Test
    void shouldRejectInvalidTargetId() {
        assertThatThrownBy(() -> RemoteSshSafetyPolicy.validateTargetId(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RemoteSshSafetyPolicy.validateTargetId("UPPERCASE"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RemoteSshSafetyPolicy.validateTargetId("has space"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
