package com.clawkit.ops.mcp;

import java.time.Duration;

public interface OpsBackend {
    OpsToolResult serviceStatus(String service);
    OpsToolResult containerStatus(String service);
    OpsToolResult ports(String service, int containerPort);
    OpsToolResult httpProbe(String endpoint);
    OpsToolResult logs(String service, Duration window, int tail);

    default OpsToolResult containerResources(String service) {
        throw new UnsupportedOperationException("container_resources is not enabled");
    }

    default OpsToolResult businessMetrics(String endpoint) {
        throw new UnsupportedOperationException("business_metrics is not enabled");
    }

    default OpsToolResult dbActivity() {
        throw new UnsupportedOperationException("db_activity is not enabled");
    }

    default OpsToolResult dbLockGraph() {
        throw new UnsupportedOperationException("db_lock_graph is not enabled");
    }

    default OpsToolResult dbConnectionStats() {
        throw new UnsupportedOperationException("db_connection_stats is not enabled");
    }
}
