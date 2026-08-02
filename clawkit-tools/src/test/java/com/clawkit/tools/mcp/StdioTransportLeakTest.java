package com.clawkit.tools.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * P0-2: Prove that raw stderr CANNOT leak through any public API.
 *
 * <p>Uses real subprocesses that emit sensitive-looking stderr.
 * Asserts that getStderrLog() (the only public accessor) returns
 * sanitized content — no key paths, IPs, user@host, or SSH_AUTH_SOCK.
 */
class StdioTransportLeakTest {

    private StdioTransport transport;

    @AfterEach
    void tearDown() {
        if (transport != null) {
            try { transport.stop(); } catch (Exception ignored) {}
        }
    }

    @Test
    void shouldNotLeakKeyPathThroughStderr() throws Exception {
        // echo emits a fake SSH stderr line with a key path
        transport = new StdioTransport(
            isWindows() ? "cmd.exe" : "sh",
            isWindows()
                ? List.of("/c", "echo Load key \"/home/user/.ssh/id_ed25519\": error in libcrypto >&2 && echo {} & exit 0")
                : List.of("-c", "echo 'Load key \"/home/user/.ssh/id_ed25519\": error in libcrypto' >&2; echo '{}'; exit 0"),
            Map.of(), Path.of("."));
        transport.start();

        // Wait for process to finish
        Thread.sleep(500);

        List<String> stderr = transport.getStderrLog();

        // The raw key path MUST NOT appear in the public stderr output
        String joined = String.join("\n", stderr);
        assertThat(joined).doesNotContain("/home/user");
        assertThat(joined).doesNotContain("id_ed25519");
        assertThat(joined).doesNotContain(".ssh");
    }

    @Test
    void shouldNotLeakIPAddressThroughStderr() throws Exception {
        transport = new StdioTransport(
            isWindows() ? "cmd.exe" : "sh",
            isWindows()
                ? List.of("/c", "echo Connection to 203.0.113.10 port 22 refused >&2 && echo {}")
                : List.of("-c", "echo 'Connection to 203.0.113.10 port 22 refused' >&2; echo '{}'"),
            Map.of(), Path.of("."));
        transport.start();
        Thread.sleep(500);
        List<String> stderr = transport.getStderrLog();
        String joined = String.join("\n", stderr);
        assertThat(joined).doesNotContain("203.0.113.10");
    }

    @Test
    void shouldNotLeakUserAtHostThroughStderr() throws Exception {
        transport = new StdioTransport(
            isWindows() ? "cmd.exe" : "sh",
            isWindows()
                ? List.of("/c", "echo Permission denied (publickey). opsro@test-server >&2 && echo {}")
                : List.of("-c", "echo 'Permission denied (publickey). opsro@test-server' >&2; echo '{}'"),
            Map.of(), Path.of("."));
        transport.start();
        Thread.sleep(500);
        List<String> stderr = transport.getStderrLog();
        String joined = String.join("\n", stderr);
        assertThat(joined).doesNotContain("opsro@test-server");
    }

    @Test
    void shouldPreserveMeaningfulErrorInfo() throws Exception {
        transport = new StdioTransport(
            isWindows() ? "cmd.exe" : "sh",
            isWindows()
                ? List.of("/c", "echo Permission denied (publickey) >&2 && echo {}")
                : List.of("-c", "echo 'Permission denied (publickey)' >&2; echo '{}'"),
            Map.of(), Path.of("."));
        transport.start();
        Thread.sleep(500);
        List<String> stderr = transport.getStderrLog();
        String joined = String.join("\n", stderr);
        // The error type should still be visible
        assertThat(joined).contains("Permission denied");
        assertThat(joined).contains("publickey");
    }

    @Test
    void rawStderrClearedAfterStop() throws Exception {
        transport = new StdioTransport(
            isWindows() ? "cmd.exe" : "sh",
            isWindows()
                ? List.of("/c", "echo some error >&2 && echo {}")
                : List.of("-c", "echo 'some error' >&2; echo '{}'"),
            Map.of(), Path.of("."));
        transport.start();
        Thread.sleep(500);
        // Should have content before stop
        assertThat(transport.getStderrLog()).isNotEmpty();
        transport.stop();
        // Must be empty after stop
        assertThat(transport.getStderrLog()).isEmpty();
    }

    @Test
    void sshAuthSockShouldNotLeak() throws Exception {
        transport = new StdioTransport(
            isWindows() ? "cmd.exe" : "sh",
            isWindows()
                ? List.of("/c", "echo SSH_AUTH_SOCK=/tmp/ssh-abc/agent.123 >&2 && echo {}")
                : List.of("-c", "echo 'SSH_AUTH_SOCK=/tmp/ssh-abc/agent.123' >&2; echo '{}'"),
            Map.of(), Path.of("."));
        transport.start();
        Thread.sleep(500);
        List<String> stderr = transport.getStderrLog();
        String joined = String.join("\n", stderr);
        assertThat(joined).doesNotContain("/tmp/ssh-abc");
        assertThat(joined).doesNotContain("agent.123");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
