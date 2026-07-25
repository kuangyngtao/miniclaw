package com.clawkit.ops.mcp;

public interface PostgresDiagnosticBackend {
    OpsToolResult dbActivity();
    OpsToolResult dbLockGraph();
    OpsToolResult dbConnectionStats();
}
