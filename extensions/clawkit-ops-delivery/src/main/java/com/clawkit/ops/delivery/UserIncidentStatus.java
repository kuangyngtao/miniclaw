package com.clawkit.ops.delivery;

/**
 * User-facing incident status — mapped deterministically from internal states.
 *
 * <p>This is the product-level status shown to the user. It hides the
 * dozens of internal engineering states behind intentional product semantics.
 *
 * <p>OPS-PRODUCT-LOOP-1 §3.
 */
public enum UserIncidentStatus {
    CREATED,
    DISCOVERING,
    DIAGNOSING,
    AWAITING_APPROVAL,
    PRECHECKING,
    EXECUTING,
    VERIFYING,

    // Terminal states
    RESOLVED,
    REJECTED,
    CANCELLED,
    NO_ACTION_REQUIRED,
    INCONCLUSIVE,
    NEEDS_HUMAN,
    FAILED_NO_EFFECT
}
