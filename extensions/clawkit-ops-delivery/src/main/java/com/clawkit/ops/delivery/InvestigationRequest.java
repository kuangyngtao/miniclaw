package com.clawkit.ops.delivery;

import java.util.Objects;

/**
 * Secret-free investigation request from the CLI layer.
 *
 * <p>Must NOT contain plaintext private keys, API keys, or passwords.
 * Target resolution happens in the CLI layer before this DTO is built.
 *
 * <p>OPS-PRODUCT-LOOP-1 §5.2.
 */
public record InvestigationRequest(
    String targetId,
    String serviceId,
    String question
) {
    public InvestigationRequest {
        Objects.requireNonNull(targetId, "targetId required");
        if (targetId.isBlank()) throw new IllegalArgumentException("targetId must not be blank");
        // serviceId may be null for free-form investigation
        if (serviceId != null && serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        if (question != null && question.isBlank()) question = null;
    }
}
