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
    Redaction redaction,
    String schemaVersion,
    CollectionStatus collectionStatus,
    Instant validUntil,
    String supersedesEvidenceId
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
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "1" : schemaVersion;
        collectionStatus = collectionStatus == null
            ? (fact.path("success").asBoolean(false)
                ? CollectionStatus.OBSERVED : CollectionStatus.COLLECTION_FAILED)
            : collectionStatus;
        if (validUntil != null && validUntil.isBefore(observedAt)) {
            throw new IllegalArgumentException("validUntil must not precede observedAt");
        }
    }

    public Evidence(
        String evidenceId, String incidentId, EvidenceType type, String source,
        Instant observedAt, Instant collectedAt, String scope, Kind kind,
        JsonNode fact, String rawReference, Freshness freshness, Redaction redaction
    ) {
        this(evidenceId, incidentId, type, source, observedAt, collectedAt, scope,
            kind, fact, rawReference, freshness, redaction, "1",
            fact.path("success").asBoolean(false)
                ? CollectionStatus.OBSERVED : CollectionStatus.COLLECTION_FAILED,
            null, null);
    }

    public enum Kind { FACT, INFERENCE }
    public enum Freshness { CURRENT, HISTORICAL, STALE }
    public enum Redaction { NONE, SENSITIVE_FIELDS_REMOVED }
    public enum CollectionStatus { OBSERVED, NORMAL, COLLECTION_FAILED }

    public boolean isCurrentAt(Instant evaluatedAt) {
        return freshness == Freshness.CURRENT
            && (validUntil == null || !evaluatedAt.isAfter(validUntil));
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
