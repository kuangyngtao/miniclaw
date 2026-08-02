package com.clawkit.ops.delivery;

/**
 * Progress event emitted during investigation.
 *
 * <p>OPS-PRODUCT-LOOP-1 §13.
 */
public record InvestigationProgress(
    int step,
    int totalSteps,
    String message,
    UserIncidentStatus currentStatus
) {
    public InvestigationProgress {
        if (step < 0) throw new IllegalArgumentException("step must be >= 0");
        if (totalSteps < 1) throw new IllegalArgumentException("totalSteps must be >= 1");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message required");
        if (currentStatus == null) throw new IllegalArgumentException("currentStatus required");
    }
}
