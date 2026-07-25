package com.clawkit.evaluation.pricing;

import java.math.BigDecimal;

/** Price per one million tokens. */
public record ModelPrice(
    String provider,
    String model,
    String currency,
    BigDecimal cacheHitInputPerMillion,
    BigDecimal cacheMissInputPerMillion,
    BigDecimal outputPerMillion
) {
    public ModelPrice {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            throw new IllegalArgumentException("provider and model must not be blank");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        requireNonNegative(cacheHitInputPerMillion, "cacheHitInputPerMillion");
        requireNonNegative(cacheMissInputPerMillion, "cacheMissInputPerMillion");
        requireNonNegative(outputPerMillion, "outputPerMillion");
    }

    public ModelKey key() {
        return new ModelKey(provider, model);
    }

    private static void requireNonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }
}
