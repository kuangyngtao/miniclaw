package com.clawkit.evaluation.pricing;

import java.math.BigDecimal;
import java.util.List;

/** Cost projection result; raw events remain the source of truth. */
public record CostProjection(
    String pricingSnapshotVersion,
    String pricingSnapshotHash,
    String currency,
    BigDecimal totalCost,
    int pricedCalls,
    int usageUnavailableCalls,
    int priceUnavailableCalls,
    List<CallCost> calls
) {
    public record CallCost(
        String providerCallId,
        String model,
        Status status,
        BigDecimal cacheHitInputCost,
        BigDecimal cacheMissInputCost,
        BigDecimal outputCost,
        BigDecimal totalCost
    ) {}

    public enum Status {
        PRICED,
        USAGE_UNAVAILABLE,
        PRICE_UNAVAILABLE
    }

    public boolean complete() {
        return usageUnavailableCalls == 0 && priceUnavailableCalls == 0;
    }
}
