package com.clawkit.tools.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for stderr sanitization in {@link StdioTransport}.
 *
 * <p>PR-1 gate: no raw SSH stderr in logs, no key paths, no IPs,
 * no Agent sockets, no user@host in diagnostic output.
 */
class StdioTransportDiagnosticTest {

    @Test
    void shouldSanitizeUnixHomeDirectory() {
        String raw = "Load key \"/home/alice/.ssh/id_ed25519\": error in libcrypto";
        String sanitized = StdioTransport.sanitizeDiagnostic(raw);
        assertThat(sanitized).doesNotContain("/home/alice");
        assertThat(sanitized).contains("[user-dir]");
        assertThat(sanitized).doesNotContain("id_ed25519");
    }

    @Test
    void shouldSanitizeWindowsHomeDirectory() {
        String raw = "Load key \"C:\\Users\\alice\\.ssh\\id_rsa\": bad permissions";
        String sanitized = StdioTransport.sanitizeDiagnostic(raw);
        assertThat(sanitized).doesNotContain("Users\\alice");
        assertThat(sanitized).doesNotContain("id_rsa");
    }

    @Test
    void shouldSanitizeMacHomeDirectory() {
        String raw = "Load key \"/Users/bob/.ssh/id_ecdsa\": invalid format";
        String sanitized = StdioTransport.sanitizeDiagnostic(raw);
        assertThat(sanitized).doesNotContain("/Users/bob");
    }

    @Test
    void shouldSanitizeKeyFilePath() {
        String raw = "Permissions 0644 for '/home/ops/.ssh/clawkit_key.pem' are too open";
        String sanitized = StdioTransport.sanitizeDiagnostic(raw);
        assertThat(sanitized).doesNotContain("clawkit_key.pem");
        assertThat(sanitized).contains("[key-path]");
    }

    @Test
    void shouldSanitizeSSHAuthSock() {
        String raw = "SSH_AUTH_SOCK=/tmp/ssh-abc123/agent.45678; export SSH_AUTH_SOCK";
        String sanitized = StdioTransport.sanitizeDiagnostic(raw);
        assertThat(sanitized).doesNotContain("/tmp/ssh-abc123");
        assertThat(sanitized).contains("[agent-socket]");
    }

    @Test
    void shouldSanitizeIPAddress() {
        String raw = "ssh: connect to host 203.0.113.10 port 22: Connection refused";
        String sanitized = StdioTransport.sanitizeDiagnostic(raw);
        assertThat(sanitized).doesNotContain("203.0.113.10");
        assertThat(sanitized).contains("[ip]");
    }

    @Test
    void shouldSanitizeUserAtHost() {
        String raw = "Permission denied (publickey). opsro@test-server.example.com";
        String sanitized = StdioTransport.sanitizeDiagnostic(raw);
        assertThat(sanitized).doesNotContain("opsro@test-server");
        assertThat(sanitized).contains("[user@host]");
    }

    @Test
    void shouldPreserveMeaningfulErrorText() {
        String raw = "Permission denied (publickey)";
        String sanitized = StdioTransport.sanitizeDiagnostic(raw);
        assertThat(sanitized).contains("Permission denied");
        assertThat(sanitized).contains("publickey");
    }

    @Test
    void shouldPreserveConnectionErrorMessage() {
        String raw = "Connection refused";
        String sanitized = StdioTransport.sanitizeDiagnostic(raw);
        assertThat(sanitized).contains("Connection refused");
    }

    @Test
    void shouldTruncateLongLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) sb.append('x');
        String sanitized = StdioTransport.sanitizeDiagnostic(sb.toString());
        assertThat(sanitized).endsWith("…");
        assertThat(sanitized.length()).isLessThanOrEqualTo(505); // 500 + "…"
    }

    @Test
    void shouldHandleNullAndEmpty() {
        assertThat(StdioTransport.sanitizeDiagnostic(null)).isEmpty();
        assertThat(StdioTransport.sanitizeDiagnostic("")).isEmpty();
    }

    @Test
    void shouldNotCorruptEmptySafeLine() {
        String raw = "debug1: Authentication succeeded (publickey)";
        String sanitized = StdioTransport.sanitizeDiagnostic(raw);
        assertThat(sanitized).contains("Authentication succeeded");
        assertThat(sanitized).doesNotContain("[user-dir]");
    }

    @Test
    void rawStderrShouldNotContainSanitizedData() {
        // Verify that getStderrLog() returns raw data but
        // getSanitizedDiagnostics() returns sanitized data.
        // This is a contract test — actual instance test requires a running
        // process, so we verify the static sanitizer behavior.
        String raw = "Load key \"/home/user/.ssh/id_rsa\": error";
        String sanitized = StdioTransport.sanitizeDiagnostic(raw);

        // Raw still contains the path (it's stashed in the ring buffer)
        assertThat(raw).contains("/home/user/.ssh/id_rsa");
        // Sanitized does not
        assertThat(sanitized).doesNotContain("/home/user");
        assertThat(sanitized).doesNotContain("id_rsa");
    }
}
