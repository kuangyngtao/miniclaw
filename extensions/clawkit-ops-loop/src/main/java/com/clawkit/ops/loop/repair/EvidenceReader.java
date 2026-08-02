package com.clawkit.ops.loop.repair;

import com.clawkit.ops.loop.Evidence;
import com.clawkit.ops.loop.EvidenceBundle;
import com.clawkit.ops.loop.EvidenceType;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic, fail-closed reader for nested {@link Evidence#fact()} JSON.
 *
 * <p>The Evidence.fact structure is:
 * <pre>{@code
 * {
 *   "success": true,
 *   "errorCode": null,
 *   "data": {
 *     "State": "running",
 *     "status": 200,
 *     "body": "...",
 *     "errorCount": 0,
 *     "p95LatencyMs": 45.2
 *   }
 * }
 * }</pre>
 *
 * <p>All field reads MUST go through {@code data()} — fact.path("State") is ALWAYS wrong.
 * Missing data, wrong type, or expired evidence → fail closed (empty Optional).
 */
public final class EvidenceReader {

    private EvidenceReader() {}

    /**
     * Find a single CURRENT, OBSERVED, non-expired evidence of the given type
     * matching the scope filter.
     */
    public static Optional<Evidence> find(EvidenceBundle bundle, EvidenceType type,
                                           String scopeContains, Instant now) {
        List<Evidence> matches = bundle.evidence().stream()
            .filter(e -> e.type() == type)
            .filter(e -> e.collectionStatus() == Evidence.CollectionStatus.OBSERVED)
            .filter(e -> e.freshness() == Evidence.Freshness.CURRENT)
            .filter(e -> e.isCurrentAt(now))
            .filter(e -> scopeContains == null || e.scope().contains(scopeContains))
            .toList();

        // Conflicting current evidence → fail closed
        if (matches.size() > 1) return Optional.empty();
        return matches.stream().findFirst();
    }

    /** Get the fact.success boolean. Always top-level. */
    public static boolean success(Evidence e) {
        JsonNode s = e.fact().path("success");
        if (s.isBoolean()) return s.asBoolean();
        return false;
    }

    /** Get the data sub-object. Missing → fail closed. */
    public static Optional<JsonNode> data(Evidence e) {
        JsonNode d = e.fact().path("data");
        if (d.isMissingNode() || d.isNull() || !d.isObject()) return Optional.empty();
        return Optional.of(d);
    }

    // ── Typed data field accessors ──

    public static Optional<String> dataString(Evidence e, String field) {
        return data(e).map(d -> d.path(field))
            .filter(n -> !n.isMissingNode() && !n.isNull() && n.isTextual())
            .map(JsonNode::asText)
            .filter(s -> !s.isBlank());
    }

    public static Optional<Integer> dataInt(Evidence e, String field) {
        return data(e).map(d -> d.path(field))
            .filter(JsonNode::isInt)
            .map(JsonNode::asInt);
    }

    public static Optional<Long> dataLong(Evidence e, String field) {
        return data(e).map(d -> d.path(field))
            .filter(n -> n.isIntegralNumber() && n.canConvertToLong())
            .map(JsonNode::asLong);
    }

    public static Optional<Double> dataDouble(Evidence e, String field) {
        return data(e).map(d -> d.path(field))
            .filter(JsonNode::isNumber)
            .map(JsonNode::asDouble);
    }

    public static Optional<Integer> dataHttpStatus(Evidence e) {
        // HTTP probe data has "statusCode" (from DockerOpsBackend) or "status"
        return data(e).flatMap(d -> {
            if (d.has("statusCode") && d.path("statusCode").isInt()) {
                return Optional.of(d.path("statusCode").asInt());
            }
            if (d.has("status") && d.path("status").isInt()) {
                return Optional.of(d.path("status").asInt());
            }
            return Optional.empty();
        });
    }

    public static Optional<String> dataBody(Evidence e) {
        return dataString(e, "body");
    }

    // ── domain-specific helpers ──

    /** Extract the State string from evidence data, handling DockerOpsBackend nesting. */
    public static Optional<String> state(Evidence e) {
        return data(e).flatMap(d -> {
            // 1. service_status via docker compose ps: data.containers[0].State
            var containers = d.path("containers");
            if (containers.isArray() && containers.size() > 0) {
                String s = containers.get(0).path("State").asText("");
                if (!s.isBlank()) return Optional.of(s);
            }
            // 2. container_status via DockerOpsBackend: data.state.Status (lowercase!)
            var stateLower = d.path("state");
            if (stateLower.isObject()) {
                String s = stateLower.path("Status").asText("");
                if (!s.isBlank()) return Optional.of(s);
            }
            // 3. container_status: data.State.Status (capital)
            var stateUpper = d.path("State");
            if (stateUpper.isObject()) {
                String s = stateUpper.path("Status").asText("");
                if (!s.isBlank()) return Optional.of(s);
            }
            // 4. Flat Status field (some backends)
            String s = d.path("Status").asText("");
            if (!s.isBlank()) return Optional.of(s);
            // 5. Fallback: direct State string
            s = d.path("State").asText("");
            if (!s.isBlank()) return Optional.of(s);
            return Optional.empty();
        });
    }

    /** Is the service/container running? */
    public static boolean isRunning(Evidence e) {
        return state(e).map(s -> s.contains("running")).orElse(false);
    }

    /** Is the service/container stopped/exited? */
    public static boolean isStoppedOrExited(Evidence e) {
        return state(e).map(s -> s.contains("stopped") || s.contains("exited")
                   || s.contains("down") || s.contains("unhealthy")).orElse(false);
    }

    /** Is the data.healthy field true? */
    public static boolean isHealthy(Evidence e) {
        return data(e).map(d -> d.path("healthy"))
            .filter(JsonNode::isBoolean)
            .map(JsonNode::asBoolean)
            .orElse(false);
    }
}
