package com.clawkit.ops.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-M1 security guardrail tests for the remote gateway, launcher, sudoers,
 * and SSH configuration.
 *
 * <p>All tests are static content analysis — they read script and config
 * sources from the project tree and verify structural invariants.
 * No {@code @Disabled} tests.
 *
 * <p>Design doc §6.1–§6.2. Execution plan §3.
 */
class GatewaySecurityTest {

    private static final Path PROJECT_ROOT = findProjectRoot();
    private static final Path GATEWAY_PATH =
        PROJECT_ROOT.resolve("ops-fixtures/remote/clawkit-ops-gateway");
    private static final Path LAUNCHER_PATH =
        PROJECT_ROOT.resolve("ops-fixtures/remote/clawkit-ops-mcp-stdio");
    private static final Path SETUP_PATH =
        PROJECT_ROOT.resolve("ops-fixtures/remote/setup-opsro.sh");
    private static final String LAUNCHER_DST = "/usr/local/sbin/clawkit-ops-mcp-stdio";

    private static Path findProjectRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("ops-fixtures/remote"))) return p;
        }
        return cwd.resolve("../../").normalize();
    }

    // ── helpers ──

    /** Lines that are not comments (don't start with # after optional whitespace). */
    private static List<String> codeLines(Path path) throws Exception {
        return Files.readAllLines(path).stream()
            .map(String::strip)
            .filter(l -> !l.isEmpty() && !l.startsWith("#"))
            .collect(Collectors.toList());
    }

    /** Full file content as a single string. */
    private static String full(Path path) throws Exception {
        return Files.readString(path);
    }

    // ── Gateway: SSH_ORIGINAL_COMMAND rejected (§6.2) ──

    @Test
    void gatewayMustRejectSshOriginalCommand() throws Exception {
        List<String> lines = codeLines(GATEWAY_PATH);
        String content = full(GATEWAY_PATH);

        // Code lines must NOT use eval (even with SSH_ORIGINAL_COMMAND)
        assertThat(lines).noneMatch(l -> l.contains("eval "));

        // Must check SSH_ORIGINAL_COMMAND and reject non-empty (§3.3)
        assertThat(content).contains("SSH_ORIGINAL_COMMAND");
        // Rejection path: non-empty → exit non-zero, no stdout
        assertThat(content).contains("exit 1");

        // Must have set -euo pipefail
        assertThat(lines).anyMatch(l -> l.contains("set -euo pipefail"));

        // Must exec sudo to the fixed launcher (only when SSH_ORIGINAL_COMMAND is empty)
        assertThat(lines).anyMatch(l -> l.contains("exec sudo") && l.contains(LAUNCHER_DST));
    }

    @Test
    void gatewayMustNotOutputBannerOrVersionInfo() throws Exception {
        List<String> lines = codeLines(GATEWAY_PATH);

        // Code lines must NOT print to stdout (no bare echo without >&2)
        for (String line : lines) {
            if (line.startsWith("echo ") && !line.contains(">&2")) {
                assertThat(line).as("gateway stdout echo: %s", line).isNull();
            }
        }

        // Full content must not have banner/welcome text outside comments
        String content = full(GATEWAY_PATH);
        // Line-level check: every "welcome" or "banner" occurrence must be in a comment
        for (String line : content.split("\n")) {
            String t = line.strip().toLowerCase();
            if ((t.contains("banner") || t.contains("welcome")) && !t.startsWith("#")) {
                assertThat(t).as("gateway has banner/welcome in non-comment: %s", line).isNull();
            }
        }
    }

    @Test
    void gatewayMustNotReadStdinForCommandInput() throws Exception {
        List<String> lines = codeLines(GATEWAY_PATH);

        // Code lines must not use "read" to consume stdin
        assertThat(lines).noneMatch(l -> l.matches(".*\\bread\\b.*"));

        // The only execution path is exec sudo
        assertThat(lines).anyMatch(l -> l.contains("exec sudo"));
    }

    // ── Launcher: no argument/env passthrough (§6.2) ──

    @Test
    void launcherMustNotPassThroughArguments() throws Exception {
        List<String> lines = codeLines(LAUNCHER_PATH);

        // No positional parameter or arg-list expansion
        for (String line : lines) {
            // Exclude substring expansions like ${VAR:offset} which use :
            assertThat(line).as("launcher uses $@/$*/$1/$2: " + line)
                .doesNotContain("$@", "$*", "$1", "$2", "${1}", "${2}");
        }

        assertThat(full(LAUNCHER_PATH)).contains("exec java");
        assertThat(full(LAUNCHER_PATH)).contains("-jar");
    }

    @Test
    void launcherMustSanitizeEnvironment() throws Exception {
        String content = full(SETUP_PATH);

        // sudoers defaults must enforce env sanitization
        assertThat(content).contains("env_reset");
        assertThat(content).contains("!setenv");
        assertThat(content).contains("secure_path");

        // NOPASSWD grant must reference the launcher (via variable or literal)
        assertThat(content).contains("NOPASSWD:");

        // The launcher path must appear somewhere in the script
        // (it's either in variable definition or echo/final content)
        assertThat(content).contains(LAUNCHER_DST);

        // SUDOERS_DEFAULTS is written before SUDOERS_CONTENT in the file output
        // — check the printf/echo that generates the final file
        assertThat(content).containsPattern(
            "SUDOERS_DEFAULTS.*SUDOERS_CONTENT|DEFAULTS.*CONTENT");
    }

    @Test
    void launcherMustEnforceFixedJarPath() throws Exception {
        List<String> lines = codeLines(LAUNCHER_PATH);

        // JAR variable must be an absolute path
        assertThat(lines).anyMatch(l -> l.startsWith("JAR=") && l.contains("\"/"));

        // Must check file existence
        assertThat(lines).anyMatch(l ->
            (l.contains("test -f") || l.contains("[[ ! -f") || l.contains("[ ! -f"))
            && l.contains("JAR"));

        // Must check permissions (group/other not writable)
        assertThat(lines).anyMatch(l -> l.contains("stat") && l.contains("JAR"));
        assertThat(lines).anyMatch(l -> l.contains("PERMS")
            || l.contains("writable"));

        // Must exit non-zero if JAR bad
        assertThat(lines).anyMatch(l -> l.contains("exit 1"));
    }

    // ── sudoers security (§6.2) ──

    @Test
    void sudoersMustOnlyAllowExactFixedLauncherPath() throws Exception {
        String content = full(SETUP_PATH);

        // The generated sudoers entry must reference the exact launcher
        assertThat(content).contains(LAUNCHER_DST);

        // SUDOERS_CONTENT line must not use wildcards
        // Find the SUDOERS_CONTENT= line
        for (String line : content.split("\n")) {
            if (line.contains("SUDOERS_CONTENT=")) {
                assertThat(line).doesNotContain("*");
                assertThat(line).doesNotContain("\"\"");
            }
        }
    }

    // ── Gateway: shell/SFTP/SCP rejection (§6.1) ──

    @Test
    void gatewayMustRejectShellSftpScpPortForwarding() throws Exception {
        List<String> lines = codeLines(SETUP_PATH);

        // authorized_keys line must use "restrict" keyword
        assertThat(lines).anyMatch(l -> l.contains("restrict") && l.contains("command="));

        // sshd_config heredoc must disable PTY, forwarding, user rc
        // The setup script writes these into sshd_config:
        String content = full(SETUP_PATH);

        // Find the SSHEOF block
        int sshStart = content.indexOf("<<'SSHEOF'");
        if (sshStart < 0) sshStart = content.indexOf("<<SSHEOF");
        int sshEnd = content.indexOf("SSHEOF", sshStart + 10);
        if (sshEnd < 0) sshEnd = content.length();
        String sshBlock = content.substring(sshStart, sshEnd);

        assertThat(sshBlock).contains("PermitTTY no");
        assertThat(sshBlock).contains("DisableForwarding yes");
        assertThat(sshBlock).contains("PermitUserRC no");
    }

    // ── Environment / config isolation ──

    @Test
    void opsMcpMainDoesNotReadSshOriginalCommand() throws Exception {
        // Verify that no Java source in the ops-mcp module reads
        // SSH_ORIGINAL_COMMAND. The MCP server must not depend on
        // any SSH-specific environment variables.
        Path srcDir = PROJECT_ROOT.resolve(
            "extensions/clawkit-ops-mcp/src/main/java");
        if (!Files.isDirectory(srcDir)) {
            // Module path differs when running from IDE — skip
            return;
        }
        try (var stream = Files.walk(srcDir)) {
            List<Path> javaFiles = stream
                .filter(p -> p.toString().endsWith(".java"))
                .toList();
            for (Path f : javaFiles) {
                String content = Files.readString(f);
                assertThat(content)
                    .as(f.getFileName() + " reads SSH_ORIGINAL_COMMAND")
                    .doesNotContain("SSH_ORIGINAL_COMMAND");
            }
            assertThat(javaFiles).as("no Java source found to scan").isNotEmpty();
        }
    }
}
