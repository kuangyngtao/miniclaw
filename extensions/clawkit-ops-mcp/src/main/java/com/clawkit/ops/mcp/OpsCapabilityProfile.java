package com.clawkit.ops.mcp;

import java.util.Locale;
import java.util.Set;

public enum OpsCapabilityProfile {
    APP_DOWN_V1(Set.of("service_status", "container_status", "ports", "http_probe", "logs")),
    POSTGRES_DIAGNOSIS_V1(Set.of(
        "service_status", "container_status", "ports", "http_probe", "logs",
        "container_resources", "business_metrics", "db_activity", "db_lock_graph",
        "db_connection_stats"));

    private final Set<String> toolNames;

    OpsCapabilityProfile(Set<String> toolNames) {
        this.toolNames = Set.copyOf(toolNames);
    }

    public Set<String> toolNames() {
        return toolNames;
    }

    public static OpsCapabilityProfile fromEnvironment(String value) {
        if (value == null || value.isBlank()) return APP_DOWN_V1;
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
