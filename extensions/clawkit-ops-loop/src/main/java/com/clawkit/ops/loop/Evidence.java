package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record Evidence(
    String evidenceId,
    String incidentId,
    EvidenceType type,
    String source,
    Instant observedAt,
    Instant collectedAt,
    String scope,
    Kind kind,
    JsonNode fact,
    String rawReference,
    Freshness freshness,
    Redaction redaction
) {
    public Evidence {
        require(evidenceId, "evidenceId");
        require(incidentId, "incidentId");
        require(source, "source");
        require(scope, "scope");
        require(rawReference, "rawReference");
        if (type == null || observedAt == null || collectedAt == null
            || kind == null || fact == null || freshness == null || redaction == null) {
            throw new IllegalArgumentException("evidence fields must not be null");
        }
        if (collectedAt.isBefore(observedAt)) {
            throw new IllegalArgumentException("collectedAt must not precede observedAt");
        }
    }

    public enum Kind { FACT, INFERENCE }
    public enum Freshness { CURRENT, HISTORICAL, STALE }
    public enum Redaction { NONE, SENSITIVE_FIELDS_REMOVED }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
