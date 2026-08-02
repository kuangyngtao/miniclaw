package com.clawkit.ops.delivery;

/**
 * Structured approval decision — never null.
 *
 * <p>Only {@link #APPROVE} creates an {@code ApprovalGrant}.
 * All other outcomes are explicit and distinct.
 *
 * <p>OPS-PRODUCT-LOOP-1 §4.
 */
public enum ApprovalDecision {
    APPROVE,
    REJECT,
    CANCEL,
    EOF,
    INTERRUPTED
}
