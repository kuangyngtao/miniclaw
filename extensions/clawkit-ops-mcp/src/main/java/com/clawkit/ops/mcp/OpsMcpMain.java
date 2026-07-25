package com.clawkit.ops.mcp;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

public final class OpsMcpMain {
    private OpsMcpMain() {}

    public static void main(String[] args) throws Exception {
        OpsTargetConfig config = OpsTargetConfig.fromEnvironment(System.getenv());
        OpsCapabilityProfile profile = OpsCapabilityProfile.fromEnvironment(
            System.getenv("CLAWKIT_OPS_PROFILE"));
        CommandExecutor commands = resolveCommandExecutor(System.getenv());
        OpsBackend backend = new DockerOpsBackend(config, commands,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
            Clock.systemUTC(), Map.of());
        if (profile == OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1) {
            backend = new CompositeOpsBackend(backend,
                JdbcPostgresDiagnosticBackend.fromEnvironment(System.getenv()));
        }
        OpsMcpServer server = new OpsMcpServer(backend, profile);
        server.serve(System.in, System.out);
    }

    static CommandExecutor resolveCommandExecutor(Map<String, String> env) {
        String sshHost = env.get("CLAWKIT_OPS_SSH_HOST");
        if (sshHost != null && !sshHost.isBlank()) {
            return new SshCommandExecutor(SshTargetConfig.fromEnvironment(env));
        }
        return new ProcessCommandExecutor();
    }
}
