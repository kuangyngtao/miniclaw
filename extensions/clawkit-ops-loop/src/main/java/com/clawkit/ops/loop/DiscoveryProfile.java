package com.clawkit.ops.loop;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A versioned discovery profile that declares which evidence to collect
 * for a specific incident type.
 *
 * <p>Design doc §8.1. Each profile statically declares the evidence specs,
 * their ordering, whether they are required vs optional, freshness TTL,
 * timeouts, and the minimum completeness condition for diagnosis.
 *
 * <p>Profiles are immutable. The coordinator must NOT read a Case manifest
 * or Ground Truth — the profile is selected based on the Incident's
 * capability profile.
 */
public record DiscoveryProfile(
    String name,
    int version,
    List<EvidenceSpec> specs,
    int minRequiredEvidence
) {
    public DiscoveryProfile {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        specs = List.copyOf(specs);
        if (specs.isEmpty()) throw new IllegalArgumentException("specs must not be empty");
        if (minRequiredEvidence < 1 || minRequiredEvidence > specs.size()) {
            throw new IllegalArgumentException("minRequiredEvidence must be between 1 and specs.size()");
        }
    }

    // ── MVP profiles (§8.1) ──

    public static final DiscoveryProfile REMOTE_APP_DOWN_V1 = new DiscoveryProfile(
        "REMOTE_APP_DOWN_V1", 1,
        List.of(
            EvidenceSpec.required("service_status", EvidenceType.SERVICE_STATUS,
                "compose/gateway", "service_status", 1,
                Duration.ofSeconds(10), Duration.ofMinutes(2),
                Map.of("service", "gateway")),
            EvidenceSpec.required("service_status", EvidenceType.SERVICE_STATUS,
                "compose/demo-api", "service_status", 2,
                Duration.ofSeconds(10), Duration.ofMinutes(2),
                Map.of("service", "demo-api")),
            EvidenceSpec.required("container_status", EvidenceType.CONTAINER_STATUS,
                "container/gateway", "container_status", 3,
                Duration.ofSeconds(10), Duration.ofMinutes(2),
                Map.of("service", "gateway")),
            EvidenceSpec.required("container_status", EvidenceType.CONTAINER_STATUS,
                "container/demo-api", "container_status", 4,
                Duration.ofSeconds(10), Duration.ofMinutes(2),
                Map.of("service", "demo-api")),
            EvidenceSpec.required("ports", EvidenceType.PORT_BINDING,
                "compose/gateway:80", "ports", 5,
                Duration.ofSeconds(10), Duration.ofMinutes(2),
                Map.of("service", "gateway", "containerPort", 80)),
            EvidenceSpec.required("http_probe", EvidenceType.HTTP_PROBE,
                "endpoint/gateway-health", "http_probe", 6,
                Duration.ofSeconds(15), Duration.ofMinutes(2),
                Map.of("endpoint", "gateway-health")),
            EvidenceSpec.optional("logs", EvidenceType.LOGS,
                "container/gateway", "logs", 7,
                Duration.ofSeconds(30), Duration.ofMinutes(5),
                Map.of("service", "gateway", "windowSeconds", 300, "tail", 100)),
            EvidenceSpec.optional("logs", EvidenceType.LOGS,
                "container/demo-api", "logs", 8,
                Duration.ofSeconds(30), Duration.ofMinutes(5),
                Map.of("service", "demo-api", "windowSeconds", 300, "tail", 100))
        ),
        6 // all 6 required specs must succeed
    );

    public static final DiscoveryProfile REMOTE_POSTGRES_DIAGNOSIS_V1 = new DiscoveryProfile(
        "REMOTE_POSTGRES_DIAGNOSIS_V1", 1,
        List.of(
            EvidenceSpec.required("service_status", EvidenceType.SERVICE_STATUS,
                "compose/gateway", "service_status", 1,
                Duration.ofSeconds(10), Duration.ofMinutes(2)),
            EvidenceSpec.required("service_status", EvidenceType.SERVICE_STATUS,
                "compose/order-api", "service_status", 2,
                Duration.ofSeconds(10), Duration.ofMinutes(2)),
            EvidenceSpec.required("container_status", EvidenceType.CONTAINER_STATUS,
                "container/postgres", "container_status", 3,
                Duration.ofSeconds(10), Duration.ofMinutes(2)),
            EvidenceSpec.required("container_resources", EvidenceType.CONTAINER_RESOURCE,
                "container/postgres", "container_resources", 4,
                Duration.ofSeconds(10), Duration.ofMinutes(2)),
            EvidenceSpec.required("business_metrics", EvidenceType.BUSINESS_METRIC,
                "metrics/k6-summary", "business_metrics", 5,
                Duration.ofSeconds(15), Duration.ofMinutes(2)),
            EvidenceSpec.required("db_activity", EvidenceType.DB_ACTIVITY,
                "db/sessions", "db_activity", 6,
                Duration.ofSeconds(15), Duration.ofMinutes(2)),
            EvidenceSpec.required("db_lock_graph", EvidenceType.DB_LOCK_GRAPH,
                "db/locks", "db_lock_graph", 7,
                Duration.ofSeconds(10), Duration.ofMinutes(2)),
            EvidenceSpec.required("db_connection_stats", EvidenceType.DB_CONNECTION_STATS,
                "db/connections", "db_connection_stats", 8,
                Duration.ofSeconds(10), Duration.ofMinutes(2)),
            EvidenceSpec.optional("logs", EvidenceType.LOGS,
                "container/postgres", "logs", 9,
                Duration.ofSeconds(30), Duration.ofMinutes(5)),
            EvidenceSpec.optional("logs", EvidenceType.LOGS,
                "container/order-api", "logs", 10,
                Duration.ofSeconds(30), Duration.ofMinutes(5))
        ),
        8 // all required specs must succeed
    );

    public List<EvidenceSpec> requiredSpecs() {
        return specs.stream().filter(EvidenceSpec::required).toList();
    }
}
