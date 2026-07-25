package com.clawkit.ops.mcp;

import java.time.Duration;
import java.util.Objects;

public final class CompositeOpsBackend implements OpsBackend {
    private final OpsBackend infrastructure;
    private final PostgresDiagnosticBackend database;

    public CompositeOpsBackend(OpsBackend infrastructure, PostgresDiagnosticBackend database) {
        this.infrastructure = Objects.requireNonNull(infrastructure);
        this.database = Objects.requireNonNull(database);
    }

    @Override public OpsToolResult serviceStatus(String service) { return infrastructure.serviceStatus(service); }
    @Override public OpsToolResult containerStatus(String service) { return infrastructure.containerStatus(service); }
    @Override public OpsToolResult ports(String service, int port) { return infrastructure.ports(service, port); }
    @Override public OpsToolResult httpProbe(String endpoint) { return infrastructure.httpProbe(endpoint); }
    @Override public OpsToolResult logs(String service, Duration window, int tail) { return infrastructure.logs(service, window, tail); }
    @Override public OpsToolResult containerResources(String service) { return infrastructure.containerResources(service); }
    @Override public OpsToolResult businessMetrics(String endpoint) { return infrastructure.businessMetrics(endpoint); }
    @Override public OpsToolResult dbActivity() { return database.dbActivity(); }
    @Override public OpsToolResult dbLockGraph() { return database.dbLockGraph(); }
    @Override public OpsToolResult dbConnectionStats() { return database.dbConnectionStats(); }
}
