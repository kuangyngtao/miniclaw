package com.clawkit.ops.loop.repair;

import com.clawkit.tools.action.ActionDescriptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Human-approved authorization for a specific repair action.
 *
 * <p>Binds: incidentId, canonicalTarget, actionFingerprint, snapshotHash, expiry.
 * All fields must match at execution time. Any drift invalidates the grant.
 * Cross-incident replay is prevented by incidentId binding.
 */
public record ApprovalGrant(
    String grantId,
    String incidentId,
    String canonicalTarget,
    String actionFingerprint,
    String snapshotHash,
    String approver,
    Instant approvedAt,
    Instant expiresAt
) {
    public ApprovalGrant {
        Objects.requireNonNull(grantId, "grantId required");
        Objects.requireNonNull(incidentId, "incidentId required");
        if (incidentId.isBlank()) throw new IllegalArgumentException("incidentId must not be blank");
        Objects.requireNonNull(canonicalTarget, "canonicalTarget required");
        if (canonicalTarget.isBlank()) throw new IllegalArgumentException("canonicalTarget must not be blank");
        Objects.requireNonNull(actionFingerprint, "actionFingerprint required");
        if (actionFingerprint.isBlank()) throw new IllegalArgumentException("actionFingerprint must not be blank");
        Objects.requireNonNull(snapshotHash, "snapshotHash required");
        if (snapshotHash.isBlank()) throw new IllegalArgumentException("snapshotHash must not be blank");
        Objects.requireNonNull(approvedAt, "approvedAt required");
        Objects.requireNonNull(expiresAt, "expiresAt required");
        if (approver == null || approver.isBlank()) approver = "cli-user";
        if (!expiresAt.isAfter(approvedAt)) {
            throw new IllegalArgumentException("expiresAt must be strictly after approvedAt");
        }
    }

    /** Default TTL: 5 minutes for CLI approval window. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    /**
     * Create a new grant. TTL must be positive.
     * @throws IllegalArgumentException if ttl is null, zero, or negative
     */
    public static ApprovalGrant create(String incidentId, String canonicalTarget,
                                        ActionDescriptor descriptor, String snapshotHash,
                                        String approver, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl required");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive, got: " + ttl);
        }
        Instant now = Instant.now();
        return new ApprovalGrant(
            UUID.randomUUID().toString(),
            incidentId,
            canonicalTarget,
            descriptor.fingerprint(),
            snapshotHash,
            approver,
            now,
            now.plus(ttl)
        );
    }

    /** Check if the grant has expired. */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Full validation against descriptor and current snapshot.
     * Checks: expiry, incidentId match, target match, fingerprint match, snapshot match.
     */
    public ValidationResult validate(ActionDescriptor descriptor, String currentSnapshotHash,
                                      String expectedIncidentId, String expectedTarget) {
        // Guard: null inputs
        if (descriptor == null) return ValidationResult.failed("descriptor is null");
        if (currentSnapshotHash == null || currentSnapshotHash.isBlank())
            return ValidationResult.failed("currentSnapshotHash is null or blank");
        if (expectedIncidentId == null || expectedIncidentId.isBlank())
            return ValidationResult.failed("expectedIncidentId is null or blank");
        if (expectedTarget == null || expectedTarget.isBlank())
            return ValidationResult.failed("expectedTarget is null or blank");

        if (isExpired()) return ValidationResult.failed("grant expired at " + expiresAt);

        // Cross-incident replay prevention
        if (!incidentId.equals(expectedIncidentId)) {
            return ValidationResult.failed("incidentId mismatch: grant was issued for "
                + incidentId + " but execution is for " + expectedIncidentId);
        }

        // Cross-target replay prevention
        if (!canonicalTarget.equals(expectedTarget)) {
            return ValidationResult.failed("canonicalTarget mismatch: grant was issued for "
                + canonicalTarget + " but execution is for " + expectedTarget);
        }

        if (!actionFingerprint.equals(descriptor.fingerprint())) {
            return ValidationResult.failed("action fingerprint mismatch: parameters, target, "
                + "risk, or verification mode changed since approval");
        }

        if (!snapshotHash.equals(currentSnapshotHash)) {
            return ValidationResult.failed("evidence snapshot changed since approval (TOCTOU): "
                + "target state may have drifted");
        }

        return ValidationResult.VALID;
    }

    /** Backward-compatible validate without incidentId/target check. */
    public ValidationResult validate(ActionDescriptor descriptor, String currentSnapshotHash) {
        return validate(descriptor, currentSnapshotHash, incidentId, canonicalTarget);
    }

    public record ValidationResult(boolean valid, String reason) {
        public static final ValidationResult VALID = new ValidationResult(true, "grant valid");
        public static ValidationResult failed(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
