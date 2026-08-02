package com.clawkit.ops.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Docker-based fix backend for the opsfix identity.
 *
 * <p>Only supports one operation: restart_service(serviceId=order-api).
 * Server-side validates serviceId again (defense in depth).
 */
public class DockerFixBackend {

    private final OpsTargetConfig config;
    private final CommandExecutor executor;
    private final ObjectMapper mapper;

    public DockerFixBackend(OpsTargetConfig config, CommandExecutor executor) {
        this.config = config;
        this.executor = executor;
        this.mapper = new ObjectMapper();
    }

    /**
     * Restart a specific service. Only order-api is allowed by the config allowlist.
     */
    public OpsToolResult restartService(String serviceId) {
        Instant observedAt = Instant.now();

        if (!"order-api".equals(serviceId)) {
            return new OpsToolResult("restart_service", "compose/" + serviceId,
                observedAt, observedAt, false, false, null,
                "INVALID_SERVICE_ID",
                "fix backend only allows order-api, got: " + serviceId,
                new OpsToolResult.Audit("docker-fix", 0, 0, 0, 0, false));
        }

        if (!config.allowedServices().contains(serviceId)) {
            return new OpsToolResult("restart_service", "compose/" + serviceId,
                observedAt, observedAt, false, false, null,
                "SERVICE_NOT_ALLOWED",
                "service '" + serviceId + "' not in allowlist: " + config.allowedServices(),
                new OpsToolResult.Audit("docker-fix", 0, 0, 0, 0, false));
        }

        long startMs = System.currentTimeMillis();
        try {
            List<String> cmd = compose("restart", serviceId);
            CommandResult result = executor.execute(cmd, Map.of(),
                config.commandTimeout(), config.maxOutputBytes());

            long durationMs = System.currentTimeMillis() - startMs;
            boolean success = result.success();
            ObjectNode data = mapper.createObjectNode()
                .put("serviceId", serviceId)
                .put("exitCode", result.exitCode())
                .put("timedOut", result.timedOut());

            return new OpsToolResult("restart_service", "compose/" + serviceId,
                observedAt, Instant.now(), true, success, data,
                success ? null : "RESTART_FAILED",
                success ? null : "docker compose restart failed: exit="
                    + result.exitCode() + " stderr=" + truncate(result.stderr(), 200),
                new OpsToolResult.Audit("docker-fix", durationMs,
                    (int) config.commandTimeout().toMillis(),
                    result.totalOutputBytes(), result.stdout().length(), result.truncated()));

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            return new OpsToolResult("restart_service", "compose/" + serviceId,
                observedAt, Instant.now(), false, false, null,
                "EXECUTION_ERROR",
                "docker compose restart error: " + e.getMessage(),
                new OpsToolResult.Audit("docker-fix", durationMs,
                    (int) config.commandTimeout().toMillis(), 0, 0, false));
        }
    }

    private List<String> compose(String subcommand, String service) {
        return List.of("docker", "compose", "--ansi", "never",
            "-f", config.composeFile().toString(),
            "-p", config.projectName(),
            subcommand, service);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
