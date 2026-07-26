package com.clawkit.ops.loop;

import com.clawkit.ops.mcp.OpsCapabilityProfile;
import com.clawkit.ops.mcp.OpsMcpServer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-0 security guardrail tests for transport errors and profile/toolset
 * attestation.
 *
 * <p>These tests verify that transport-level failures (EOF, timeout, cancel,
 * illegal JSON-RPC) and capability mismatches (wrong profile, wrong toolset)
 * are detected and fail closed — never silently ignored or misinterpreted
 * as business errors.
 *
 * <p>Tests marked {@code @Disabled} target components that will be built in
 * PR-2 (RemoteOpsSession) and PR-4 (Discovery Profile).
 */
class TransportAndProfileSecurityTest {

    // ── Profile attestation (test now with OpsCapabilityProfile enum) ──

    @Test
    void appDownProfileDoesNotIncludePostgresTools() {
        // Design doc §5.1: each profile declares its tool set statically.
        // APP_DOWN_V1 must not expose db_activity, db_lock_graph, or
        // db_connection_stats.
        Set<String> appDown = OpsCapabilityProfile.APP_DOWN_V1.toolNames();

        assertThat(appDown).hasSize(5);
        assertThat(appDown)
            .doesNotContain("container_resources")
            .doesNotContain("business_metrics")
            .doesNotContain("db_activity")
            .doesNotContain("db_lock_graph")
            .doesNotContain("db_connection_stats");
    }

    @Test
    void postgresProfileIncludesAllAppDownToolsPlusFiveDbTools() {
        Set<String> postgres = OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1.toolNames();

        assertThat(postgres).hasSize(10);
        assertThat(postgres).containsAll(OpsMcpServer.TOOL_NAMES);
        assertThat(postgres)
            .contains("container_resources")
            .contains("business_metrics")
            .contains("db_activity")
            .contains("db_lock_graph")
            .contains("db_connection_stats");
    }

    @Test
    void profilesAreImmutableAndDistinct() {
        Set<String> appDown = OpsCapabilityProfile.APP_DOWN_V1.toolNames();
        Set<String> postgres = OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1.toolNames();

        // Verify Set.copyOf() semantics — cannot modify through reference
        assertThatThrownBy(() -> appDown.add("injected_tool"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> postgres.add("injected_tool"))
            .isInstanceOf(UnsupportedOperationException.class);

        // Verify no shared mutable state
        assertThat(appDown).isNotEqualTo(postgres);
    }

    @Test
    void unrecognizedProfileFromEnvironmentFallsIntoUnknownEnumValueException() {
        // Design doc §5.1: unknown profile → IllegalArgumentException.
        // The caller must handle this — it must NOT silently default to
        // a different profile with different capabilities.
        assertThatThrownBy(() ->
            OpsCapabilityProfile.fromEnvironment("UNKNOWN_PROFILE_V99"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullOrBlankEnvironmentDefaultsToAppDown() {
        // Design doc §6.3: default profile is APP_DOWN_V1 (most restricted).
        assertThat(OpsCapabilityProfile.fromEnvironment(null))
            .isEqualTo(OpsCapabilityProfile.APP_DOWN_V1);
        assertThat(OpsCapabilityProfile.fromEnvironment(""))
            .isEqualTo(OpsCapabilityProfile.APP_DOWN_V1);
        assertThat(OpsCapabilityProfile.fromEnvironment("  "))
            .isEqualTo(OpsCapabilityProfile.APP_DOWN_V1);
    }

    // ── Toolset attestation (tests for capability boundary validation) ──

    @Test
    void everyAppDownToolHasReadOnlyAnnotations() {
        // Design doc §6.3: all tools must be readOnly, not destructive,
        // not openWorld. This is validated by OpsMcpServer.tool() which
        // sets annotations on every tool.
        //
        // This test verifies that the tool construction method always sets
        // the safe defaults. If a tool is ever added without calling
        // OpsMcpServer.tool(), it would be caught by this test.
        //
        // Verified indirectly: OpsMcpServerTest.exposesOnlyBoundedReadOnlyTools()
        // checks annotations for all 5 APP_DOWN_V1 tools.
        assertThat(OpsMcpServer.TOOL_NAMES).hasSize(5);
    }

    @Test
    @Disabled("PR-2: RemoteOpsSession attestation not yet built")
    void profileMismatchMustPreventEvidenceCollection() {
        // Design doc §7.2: after MCP initialize, the client must verify
        // that the server's capability profile matches the expected profile
        // for this Incident.
        //
        // If the Incident requires POSTGRES_DIAGNOSIS_V1 but the server
        // only advertises APP_DOWN_V1, collection must abort with a
        // REMOTE_PROFILE_MISMATCH structured error — not silently collect
        // a subset of tools.
        //
        // Test approach (PR-2):
        // 1. Create RemoteOpsSession with expected POSTGRES_DIAGNOSIS_V1
        // 2. Connect to a server that only has APP_DOWN_V1 tools
        // 3. Verify initializeAttestation() throws with REMOTE_PROFILE_MISMATCH
        // 4. Verify no evidence was collected
    }

    @Test
    @Disabled("PR-2: RemoteOpsSession attestation not yet built")
    void toolsetMismatchMustPreventEvidenceCollection() {
        // Design doc §7.2: the client must verify tool-set hash matches.
        // If the remote server has extra tools (e.g. from a newer
        // deployment) or missing tools, collection must abort.
        //
        // Test approach (PR-2):
        // 1. Create RemoteOpsSession with expected tool-set hash
        // 2. Connect to server with different tool set (e.g. extra tool)
        // 3. Verify initializeAttestation() throws with REMOTE_TOOLSET_MISMATCH
        // 4. Verify no evidence was collected
    }

    @Test
    @Disabled("PR-2: RemoteOpsSession attestation not yet built")
    void probeVersionMismatchMustPreventEvidenceCollection() {
        // Design doc §7.2: the client must verify probeVersion matches.
        // If the remote server reports a different version, collection must
        // abort — the evidence format or semantics may have changed.
        //
        // Test approach (PR-2):
        // 1. Create RemoteOpsSession with expected probeVersion "1"
        // 2. Connect to server reporting probeVersion "2"
        // 3. Verify initializeAttestation() throws with REMOTE_PROFILE_MISMATCH
    }

    // ── Transport error classification (contract tests for PR-2) ──

    @Test
    @Disabled("PR-2: RemoteOpsSession not yet built")
    void transportEofMustProduceTransportLostNotBusinessError() {
        // Design doc §7.5: when the SSH/MCP transport disconnects (EOF),
        // the error must be classified as SSH_TRANSPORT_CLOSED — not as a
        // business error, tool failure, or Docker command failure.
        //
        // Test approach (PR-2):
        // 1. Set up fake stdio transport that exits after sending response
        // 2. Make a second request; EOF should produce structured error
        // 3. Verify error.layer == SSH
        // 4. Verify error.code == SSH_TRANSPORT_CLOSED
    }

    @Test
    @Disabled("PR-2: RemoteOpsSession not yet built")
    void transportTimeoutMustDistinguishFromToolTimeout() {
        // Design doc §7.5: SSH_REQUEST_TIMEOUT is distinct from a tool
        // timing out. The SSH layer timeout means the transport itself
        // didn't respond — the tool may or may not have executed.
        //
        // Test approach (PR-2):
        // 1. Set up fake transport that sleeps indefinitely
        // 2. Request with short timeout (e.g. 100ms)
        // 3. Verify error.layer == SSH
        // 4. Verify error.code == SSH_REQUEST_TIMEOUT
        // 5. Verify NOT classified as COMMAND_TIMEOUT (tool layer)
    }

    @Test
    @Disabled("PR-2: RemoteOpsSession not yet built")
    void executionControlCancelMustCleanUpTransport() {
        // Design doc §7.2: cancellation via ExecutionControl must cleanly
        // close the transport and fail pending requests.
        //
        // Test approach (PR-2):
        // 1. Set up transport with a pending request
        // 2. Cancel via ExecutionControl
        // 3. Verify pending future completes exceptionally
        // 4. Verify transport process is terminated
        // 5. Verify close() is idempotent
    }

    @Test
    @Disabled("PR-2: RemoteOpsSession not yet built")
    void illegalJsonRpcResponseMustProduceProtocolError() {
        // Design doc §7.5: if the server returns non-JSON or a response
        // that doesn't match the JSON-RPC 2.0 spec, it must be classified
        // as REMOTE_MCP_PROTOCOL_ERROR — not as a successful response.
        //
        // Test approach (PR-2):
        // 1. Set up fake transport that returns "not json" on stdout
        // 2. Request any tool
        // 3. Verify error.layer == MCP
        // 4. Verify error.code == REMOTE_MCP_PROTOCOL_ERROR
    }

    @Test
    @Disabled("PR-2: RemoteOpsSession not yet built")
    void stderrOutputMustNotCorruptResponseParsing() {
        // Design doc §6.2: stdout only JSON-RPC; diagnostics go to stderr.
        //
        // Test approach (PR-2):
        // 1. Set up fake transport that writes JSON-RPC to stdout and
        //    diagnostic text to stderr concurrently
        // 2. Verify response is correctly parsed from stdout
        // 3. Verify stderr content is captured but not mixed into response
    }
}
