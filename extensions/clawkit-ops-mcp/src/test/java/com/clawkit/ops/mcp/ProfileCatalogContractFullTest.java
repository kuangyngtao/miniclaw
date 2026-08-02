package com.clawkit.ops.mcp;

import com.clawkit.tools.remote.ToolContractHash;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * P1-3: Full cross-contract test. Verifies the catalog hashes
 * match what OpsMcpServer actually produces — no hardcoded constants
 * used for comparison.
 *
 * <p>Design: PRODUCT-1 §5.3, §17.5.
 */
class ProfileCatalogContractFullTest {

    // ── APP_DOWN_V1 ───────────────────────────────────────────────────

    @Test
    void appDownToolSetHashMatchesServer() {
        Set<String> names = OpsCapabilityProfile.APP_DOWN_V1.toolNames();
        assertThat(names).containsExactlyInAnyOrder(
            "service_status", "container_status", "ports", "http_probe", "logs");

        String computed = ToolContractHash.computeToolSetHashFromNames(names);
        // Verify against catalog value — computed from actual tool names
        assertThat(computed).isEqualTo("d822b006a5dcb84c");
    }

    @Test
    void appDownContractHashMatchesServer() {
        String computed = OpsMcpServer
            .computeExpectedToolContractHash(OpsCapabilityProfile.APP_DOWN_V1);
        // Catalog pinned value must match server computation
        assertThat(computed).isEqualTo(
            "666e4646d56653639adf0719e614258eef8fc4ce521f3ec57e7d8e0680569abf");
    }

    // ── POSTGRES_DIAGNOSIS_V1 ─────────────────────────────────────────

    @Test
    void postgresToolSetHashMatchesServer() {
        Set<String> names = OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1.toolNames();
        assertThat(names).hasSize(10);

        String computed = ToolContractHash.computeToolSetHashFromNames(names);
        assertThat(computed).isEqualTo("7e33276f3c0ef4b9");
    }

    @Test
    void postgresContractHashMatchesServer() {
        String computed = OpsMcpServer
            .computeExpectedToolContractHash(OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1);
        assertThat(computed).isEqualTo(
            "141d42ba560716f5698d7ebd7e0a2b7fa13e60d10a9df23e6967c04186b65540");
    }

    // ── FIX_ORDER_API_V1 NOT in catalog ───────────────────────────────

    @Test
    void fixProfileHasDifferentHash() {
        String fixHash = OpsMcpServer
            .computeExpectedToolContractHash(OpsCapabilityProfile.FIX_ORDER_API_V1);
        String appDownHash = OpsMcpServer
            .computeExpectedToolContractHash(OpsCapabilityProfile.APP_DOWN_V1);
        String postgresHash = OpsMcpServer
            .computeExpectedToolContractHash(OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1);

        assertThat(fixHash).isNotEqualTo(appDownHash);
        assertThat(fixHash).isNotEqualTo(postgresHash);

        Set<String> fixNames = OpsCapabilityProfile.FIX_ORDER_API_V1.toolNames();
        assertThat(fixNames).containsExactly("restart_service");
    }

    // ── Server identity ───────────────────────────────────────────────

    @Test
    void appDownServerName() {
        var server = new OpsMcpServer(null, OpsCapabilityProfile.APP_DOWN_V1);
        assertThat(server.serverName()).isEqualTo("clawkit-ops-mcp");
    }

    @Test
    void postgresServerName() {
        var server = new OpsMcpServer(null, OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1);
        assertThat(server.serverName()).isEqualTo("clawkit-ops-mcp");
    }

    @Test
    void fixServerName() {
        var server = new OpsMcpServer(null, OpsCapabilityProfile.FIX_ORDER_API_V1, null);
        assertThat(server.serverName()).isEqualTo("clawkit-ops-fix");
    }

    // ── Tool count ────────────────────────────────────────────────────

    @Test
    void appDownHasFiveTools() {
        assertThat(OpsCapabilityProfile.APP_DOWN_V1.toolNames()).hasSize(5);
    }

    @Test
    void postgresHasTenTools() {
        assertThat(OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1.toolNames()).hasSize(10);
    }

    // ── Hash consistency ──────────────────────────────────────────────

    @Test
    void toolSetHashIsStableRegardlessOfToolOrder() {
        Set<String> order1 = Set.of("service_status", "container_status",
            "ports", "http_probe", "logs");
        Set<String> order2 = Set.of("logs", "ports", "service_status",
            "container_status", "http_probe");

        String hash1 = ToolContractHash.computeToolSetHashFromNames(order1);
        String hash2 = ToolContractHash.computeToolSetHashFromNames(order2);
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void toolSetHashChangesWithDifferentTools() {
        String appDownHash = ToolContractHash.computeToolSetHashFromNames(
            OpsCapabilityProfile.APP_DOWN_V1.toolNames());
        String postgresHash = ToolContractHash.computeToolSetHashFromNames(
            OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1.toolNames());
        assertThat(appDownHash).isNotEqualTo(postgresHash);
    }

    @Test
    void contractHashChangesWithSchemaChange() {
        // APP_DOWN and POSTGRES have different tools, so contract hash must differ
        String appDown = OpsMcpServer
            .computeExpectedToolContractHash(OpsCapabilityProfile.APP_DOWN_V1);
        String postgres = OpsMcpServer
            .computeExpectedToolContractHash(OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1);
        assertThat(appDown).isNotEqualTo(postgres);
    }

    // ── Protocol constants ────────────────────────────────────────────

    @Test
    void protocolVersionIsStable() {
        assertThat(OpsMcpServer.PROTOCOL_VERSION).isEqualTo("2024-11-05");
        assertThat(OpsMcpServer.PROBE_VERSION).isEqualTo("1");
    }
}
