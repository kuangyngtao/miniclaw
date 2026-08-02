package com.clawkit.ops.mcp;

import com.clawkit.tools.remote.ToolContractHash;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Cross-contract test: verifies that the built-in profile catalog tool set
 * hashes are consistent with the tool names defined in {@link OpsCapabilityProfile}.
 *
 * <p>Full contract hash verification is handled by {@code Remote0CliE2ETest}
 * which connects to a real OpsMcpServer and performs complete attestation.
 *
 * <p>Design: PRODUCT-1 §5.3, §17.5.
 */
class ProfileCatalogContractTest {

    // Pinned tool set hashes from RemoteProfileCatalog
    private static final String APP_DOWN_TOOL_SET_HASH = "d822b006a5dcb84c";
    private static final String POSTGRES_TOOL_SET_HASH = "7e33276f3c0ef4b9";
    private static final String FIX_TOOL_SET_HASH = "b2452c4a7e8f1d36";

    @Test
    void appDownToolSetHashShouldMatchCatalog() {
        Set<String> toolNames = OpsCapabilityProfile.APP_DOWN_V1.toolNames();
        assertThat(toolNames).containsExactlyInAnyOrder(
            "service_status", "container_status", "ports", "http_probe", "logs");

        String computed = ToolContractHash.computeToolSetHashFromNames(toolNames);
        assertThat(computed)
            .as("APP_DOWN_V1 toolSetHash drift — update RemoteProfileCatalog")
            .isEqualTo(APP_DOWN_TOOL_SET_HASH);
    }

    @Test
    void postgresToolSetHashShouldMatchCatalog() {
        Set<String> toolNames = OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1.toolNames();
        assertThat(toolNames).contains(
            "service_status", "container_status", "ports", "http_probe", "logs",
            "container_resources", "business_metrics", "db_activity",
            "db_lock_graph", "db_connection_stats");

        String computed = ToolContractHash.computeToolSetHashFromNames(toolNames);
        assertThat(computed)
            .as("POSTGRES_DIAGNOSIS_V1 toolSetHash drift — update RemoteProfileCatalog")
            .isEqualTo(POSTGRES_TOOL_SET_HASH);
    }

    @Test
    void appDownServerNameShouldBeCorrect() {
        assertThat(OpsMcpServer.PROTOCOL_VERSION).isEqualTo("2024-11-05");
        assertThat(OpsMcpServer.PROBE_VERSION).isEqualTo("1");

        var server = new OpsMcpServer(null, OpsCapabilityProfile.APP_DOWN_V1);
        assertThat(server.serverName()).isEqualTo("clawkit-ops-mcp");
    }

    @Test
    void postgresServerNameShouldBeCorrect() {
        var server = new OpsMcpServer(null, OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1);
        assertThat(server.serverName()).isEqualTo("clawkit-ops-mcp");
    }

    @Test
    void fixServerNameShouldBeDistinct() {
        var server = new OpsMcpServer(null, OpsCapabilityProfile.FIX_ORDER_API_V1, null);
        assertThat(server.serverName()).isEqualTo("clawkit-ops-fix");
    }
}
