package com.clawkit.ops.mcp;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

public final class OpsMcpMain {
    private OpsMcpMain() {}

    public static void main(String[] args) throws Exception {
        // PR-M4: hard-reject the legacy SSH command executor path
        String sshHost = System.getenv("CLAWKIT_OPS_SSH_HOST");
        if (sshHost != null && !sshHost.isBlank()) {
            System.err.println("[clawkit-ops-mcp] ERROR: CLAWKIT_OPS_SSH_HOST is no longer supported.");
            System.err.println("[clawkit-ops-mcp] Remote OPS now uses forced-command MCP stdio session.");
            System.err.println("[clawkit-ops-mcp] Use RemoteOpsSession or RemoteDiscoveryMain instead.");
            System.exit(4);
        }

        OpsTargetConfig config = OpsTargetConfig.fromEnvironment(System.getenv());
        OpsCapabilityProfile profile = OpsCapabilityProfile.fromEnvironment(
            System.getenv("CLAWKIT_OPS_PROFILE"));
        OpsBackend backend = new DockerOpsBackend(config, new ProcessCommandExecutor(),
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
            Clock.systemUTC(), java.util.Map.of());
        if (profile == OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1) {
            backend = new CompositeOpsBackend(backend,
                JdbcPostgresDiagnosticBackend.fromEnvironment(System.getenv()));
        }
        OpsMcpServer server = new OpsMcpServer(backend, profile);
        server.serve(System.in, System.out);
    }
}
