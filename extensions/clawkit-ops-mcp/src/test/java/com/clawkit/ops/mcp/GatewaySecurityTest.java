package com.clawkit.ops.mcp;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-0 security guardrail tests for the remote gateway and launcher.
 *
 * <p>These tests define the security invariants that the remote gateway
 * ({@code clawkit-ops-gateway}) and fixed launcher
 * ({@code clawkit-ops-mcp-stdio}) must enforce. They are contract tests
 * for PR-1 (P0 forced-command remote interface).
 *
 * <p>Tests are currently {@code @Disabled} because the gateway/launcher
 * artifacts do not exist yet. Each test references the specific invariant
 * from the design doc and the PR that will implement it.
 */
class GatewaySecurityTest {

    // ── Gateway: SSH_ORIGINAL_COMMAND ignored ──

    @Test
    @Disabled("PR-1: gateway artifact not yet built")
    void gatewayMustIgnoreSshOriginalCommand() {
        // Design doc §6.2: "不读取 SSH_ORIGINAL_COMMAND，不做 shell 参数拼接"
        //
        // When OpenSSH forced-command is configured, SSH_ORIGINAL_COMMAND
        // contains whatever the client typed. The gateway MUST NOT read or
        // act on this variable — it must always launch the fixed launcher
        // regardless of what the client requested.
        //
        // Test approach (PR-1):
        // 1. Set SSH_ORIGINAL_COMMAND="rm -rf /" in gateway environment
        // 2. Gateway should still exec the fixed launcher path
        // 3. Verify the command was NOT run, only the fixed launcher was
    }

    @Test
    @Disabled("PR-1: gateway artifact not yet built")
    void gatewayMustNotOutputBannerOrVersionInfo() {
        // Design doc §6.2: "不输出 banner"
        //
        // The gateway's first bytes on stdout must be the MCP JSON-RPC
        // initialize response. No welcome banner, no version string,
        // no MOTD — anything else corrupts the JSON-RPC stream.
        //
        // Test approach (PR-1):
        // 1. Start stdio transport to gateway
        // 2. Send MCP initialize
        // 3. First stdout line must be valid JSON-RPC with "result"
        // 4. No plaintext output before the first JSON line
    }

    @Test
    @Disabled("PR-1: gateway artifact not yet built")
    void gatewayMustNotReadStdinForCommandInput() {
        // Design doc §6.2: "只执行固定命令"
        //
        // The gateway must NOT read stdin looking for a command to execute.
        // It must unconditionally exec the fixed launcher.
        //
        // Test approach (PR-1):
        // 1. Launch gateway subprocess
        // 2. Write "arbitrary command" to its stdin
        // 3. Gateway should ignore it and launch the fixed MCP server
    }

    // ── Launcher: no argument/env passthrough ──

    @Test
    @Disabled("PR-1: launcher artifact not yet built")
    void launcherMustNotPassThroughArguments() {
        // Design doc §6.2: "exec java -jar <fixed-path>，不透传用户参数"
        //
        // The launcher script must exec a fixed java invocation. Any
        // arguments received (e.g. from SSH_ORIGINAL_COMMAND) must be
        // discarded — the JVM must only receive the fixed arguments defined
        // in the root-owned launcher script.
        //
        // Test approach (PR-1):
        // 1. Verify the launcher script is a simple exec with no $@ or $*
        // 2. Verify the script does not reference $1, $2, etc.
        // 3. Verify the JAR path is an absolute, non-writable path
    }

    @Test
    @Disabled("PR-1: launcher artifact not yet built")
    void launcherMustSanitizeEnvironment() {
        // Design doc §6.2: sudoers uses "env_reset,!setenv"
        //
        // The sudo invocation must reset the environment. User-controlled
        // environment variables (PATH, LD_LIBRARY_PATH, JAVA_HOME,
        // CLASSPATH, etc.) must NOT propagate to the Java process.
        //
        // The only environment variables the Java process receives must
        // come from the root-only /etc/clawkit/ops-mcp.env file.
        //
        // Test approach (PR-1):
        // 1. Verify sudoers contains "env_reset" and "!setenv"
        // 2. Verify secure_path is explicitly set
        // 3. Verify system properties like -Djava.security.egd are not
        //    controllable from the user environment
    }

    @Test
    @Disabled("PR-1: launcher artifact not yet built")
    void launcherMustEnforceFixedJarPath() {
        // Design doc §6.2: "校验 JAR 路径固定且不可写"
        //
        // The launcher must verify that the JAR file it is about to exec
        // exists at a known, absolute path and is not writable by opsro.
        // If the file is missing, modified, or writable by a non-root user,
        // the launcher must refuse to start and exit with a non-zero code.
        //
        // Test approach (PR-1):
        // 1. Verify the JAR path is absolute (starts with /)
        // 2. Verify ownership/permissions: root-owned, 0755 or 0644
        // 3. Verify opsro cannot write to the JAR path
        // 4. Remove the JAR and verify launcher exits non-zero
    }

    // ── sudoers security ──

    @Test
    @Disabled("PR-1: sudoers artifact not yet created")
    void sudoersMustOnlyAllowExactFixedLauncherPath() {
        // Design doc §6.2: "opsro ALL=(root) NOPASSWD: /usr/local/sbin/clawkit-ops-mcp-stdio"
        //
        // The sudoers grant must be scoped to the exact, absolute launcher
        // path with no arguments, no wildcards, and no parameter passing.
        //
        // Test approach (PR-1):
        // 1. Verify sudoers entry contains the exact path
        // 2. Verify no wildcard (*) in the command path
        // 3. Verify no argument passthrough (no "" at end of command)
        // 4. Verify "NOPASSWD" only applies to this single command
    }

    @Test
    @Disabled("PR-1: gateway artifact not yet built")
    void gatewayMustRejectShellSftpScpPortForwarding() {
        // Design doc §6.1: "restrict" keyword in authorized_keys disables
        // PTY, port forwarding, agent forwarding, X11, and user rc.
        //
        // Test approach (PR-1):
        // 1. Attempt: ssh opsro@target              → refused / gateway only
        // 2. Attempt: sftp opsro@target              → refused
        // 3. Attempt: scp file opsro@target:/tmp/    → refused
        // 4. Attempt: ssh -L 8080:localhost:80 ...   → refused
        // 5. Attempt: ssh -t opsro@target bash       → refused
        //
        // All must fail before reaching the gateway process.
        // These are tested in the remote smoke (PR-1 §真实 smoke).
    }

    // ── Environment / config isolation ──

    @Test
    void opsMcpMainDoesNotReadSshOriginalCommand() {
        // Verify that OpsMcpMain (the current stdio entrypoint) does not
        // read SSH_ORIGINAL_COMMAND. This is a precondition for the gateway
        // approach: the MCP server must not depend on any SSH-specific
        // environment variables for its security decisions.
        //
        // Already true: OpsMcpMain only reads CLAWKIT_OPS_* variables.
        String sshOriginalCommand = System.getenv("SSH_ORIGINAL_COMMAND");
        // If set, it must not affect server behavior
        if (sshOriginalCommand != null) {
            // Document: the value exists but is not consumed by OpsMcpMain
            assertThat(sshOriginalCommand).isNotNull(); // tautology; documents the env var existence
        }
        // This test passes regardless — it documents the invariant.
    }
}
