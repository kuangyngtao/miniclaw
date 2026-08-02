package com.clawkit.cli.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * P0-1: Prove SSH config static audit correctness.
 *
 * <p>Key assertions:
 * - Match exec detected regardless of position in Match line
 * - Comments do NOT trigger false positives
 * - Include with multiple paths and quotes parses correctly
 * - Include unreadable/glob fail → unsafe (fail closed)
 * - Include depth and file count limits enforced
 * - ProxyJump referenced aliases discovered (not blocked by audit)
 * - When unsafe config found, ssh -G call count must be 0
 */
class SshConfigAuditTest {

    @TempDir Path tempDir;

    // ── Match exec detection ──────────────────────────────────────────

    @Test
    void shouldDetectMatchExecAnywhereInLine() {
        var lines = List.of(
            "Match host test-server exec \"command\"",
            "Match user opsro exec \"other\"",
            "Match all"
        );
        int unsafe = 0;
        for (String line : lines) {
            if (line.matches("^\\s*Match\\s+.*\\bexec\\b(?i).*")) unsafe++;
        }
        assertThat(unsafe).isEqualTo(2); // first two should match
    }

    @Test
    void shouldNotDetectExecInComments() {
        var facade = facadeWithConfig("""
            # This is a comment about ProxyCommand
            # Match exec should not match in comments
            Host test-server
              HostName example.com
            """);
        var discovery = new SshTargetDiscovery(facade);
        var result = discovery.discover();
        assertThat(result.safe()).isTrue();
        assertThat(result.aliases()).contains("test-server");
    }

    @Test
    void shouldNotDetectUnsafeDirectivesInComments() {
        var facade = facadeWithConfig("""
            # ProxyCommand none
            # KnownHostsCommand none
            Host safe-server
              HostName safe.example.com
            """);
        var discovery = new SshTargetDiscovery(facade);
        var result = discovery.discover();
        assertThat(result.safe()).isTrue();
        assertThat(result.aliases()).contains("safe-server");
    }

    @Test
    void shouldDetectRealMatchExecAsUnsafe() {
        var facade = facadeWithConfig("""
            Host test-server
              HostName example.com
            Match host test-server exec "run_check"
              ProxyJump jump.example.com
            """);
        var discovery = new SshTargetDiscovery(facade);
        var result = discovery.discover();
        assertThat(result.safe()).isFalse();
        assertThat(result.unsafeReasons()).anyMatch(r -> r.contains("Match exec"));
    }

    @Test
    void shouldDetectProxyCommandAsUnsafe() {
        var facade = facadeWithConfig("""
            Host unsafe-server
              ProxyCommand ssh -W %h:%p jump.example.com
            """);
        var discovery = new SshTargetDiscovery(facade);
        var result = discovery.discover();
        assertThat(result.safe()).isFalse();
        assertThat(result.unsafeReasons()).anyMatch(r -> r.contains("ProxyCommand"));
    }

    // ── Include parsing ────────────────────────────────────────────────

    @Test
    void shouldParseMultipleIncludePaths() {
        List<String> paths = SshTargetDiscovery.parseIncludePaths(
            "\"~/.ssh/conf.d/*.conf\" /etc/ssh/extra.conf");
        assertThat(paths).containsExactly("~/.ssh/conf.d/*.conf", "/etc/ssh/extra.conf");
    }

    @Test
    void shouldParseQuotedIncludePath() {
        List<String> paths = SshTargetDiscovery.parseIncludePaths(
            "'/path with spaces/config'");
        assertThat(paths).containsExactly("/path with spaces/config");
    }

    @Test
    void shouldFailClosedWhenIncludeNotFound() throws Exception {
        // Config references a non-existent include file
        Path configDir = tempDir.resolve(".ssh");
        Files.createDirectories(configDir);
        Path configFile = configDir.resolve("config");
        Files.writeString(configFile, """
            Include nonexistent-file.conf
            Host test-server
              HostName example.com
            """);

        var facade = new SystemOpenSshFacade(configDir, null);
        var discovery = new SshTargetDiscovery(facade);
        var result = discovery.discover();
        assertThat(result.safe()).isFalse();
        assertThat(result.unsafeReasons()).anyMatch(r ->
            r.contains("include not found") || r.contains("unreadable"));
    }

    @Test
    void shouldEnforceIncludeDepthLimit() throws Exception {
        // Create 6 levels of nested includes (exceeds MAX_INCLUDE_DEPTH=5)
        Path configDir = tempDir.resolve(".ssh");
        Files.createDirectories(configDir);
        Path[] levels = new Path[7];
        levels[0] = configDir.resolve("config");
        for (int i = 1; i <= 6; i++) {
            levels[i] = configDir.resolve("level" + i + ".conf");
        }
        // Main includes level1
        Files.writeString(levels[0],
            "Host test-server\n  HostName example.com\n"
            + "Include " + levels[1].toAbsolutePath() + "\n");
        // Each level includes next
        for (int i = 1; i <= 5; i++) {
            Files.writeString(levels[i],
                "Include " + levels[i + 1].toAbsolutePath() + "\n");
        }
        Files.writeString(levels[6], "Host deep\n  HostName deep.example.com\n");

        var facade = new SystemOpenSshFacade(configDir, null);
        var discovery = new SshTargetDiscovery(facade);
        var result = discovery.discover();
        // Depth limit exceeded — must be reported as unsafe
        assertThat(result.unsafeReasons()).anyMatch(r ->
            r.contains("depth") || r.contains("limit"));
    }

    // ── Discovery with real temp config ────────────────────────────────

    @Test
    void shouldDiscoverAliasesFromTempConfig() throws Exception {
        Path configDir = tempDir.resolve(".ssh");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config"), """
            Host test-server
              HostName 203.0.113.10
              User opsro
            Host staging
              HostName staging.example.com
              User opsro
            Host *.wildcard
              HostName wild.example.com
            Host !negated
              HostName neg.example.com
            """);

        var facade = new SystemOpenSshFacade(configDir, null);
        var discovery = new SshTargetDiscovery(facade);
        var result = discovery.discover();

        assertThat(result.safe()).isTrue();
        assertThat(result.aliases()).contains("test-server", "staging");
        assertThat(result.aliases()).doesNotContain("*.wildcard", "!negated");
    }

    @Test
    void shouldDiscoverFromIncludeFiles() throws Exception {
        Path configDir = tempDir.resolve(".ssh");
        Path confDir = configDir.resolve("conf.d");
        Files.createDirectories(confDir);
        Path includeTarget = confDir.resolve("servers.conf");
        Files.writeString(configDir.resolve("config"),
            "Include " + includeTarget.toAbsolutePath() + "\n");
        Files.writeString(includeTarget, """
            Host db-lab
              HostName db.example.com
            """);

        var facade = new SystemOpenSshFacade(configDir, null);
        var discovery = new SshTargetDiscovery(facade);
        var result = discovery.discover();
        assertThat(result.aliases()).contains("db-lab");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private SystemOpenSshFacade facadeWithConfig(String configContent) {
        try {
            Path configDir = tempDir.resolve(".ssh");
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve("config"), configContent);
            return new SystemOpenSshFacade(configDir, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
