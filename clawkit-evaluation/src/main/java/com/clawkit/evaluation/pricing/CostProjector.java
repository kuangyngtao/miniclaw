package com.clawkit.evaluation.pricing;

import com.clawkit.observability.ProviderCallCompletedPayload;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Projects immutable usage events through an explicit pricing snapshot. */
public final class CostProjector {
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    private CostProjector() {}

    public static CostProjection project(List<ProviderCallCompletedPayload> events,
                                         PricingSnapshot snapshot,
                                         String provider) {
        List<CostProjection.CallCost> calls = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int priced = 0;
        int usageUnavailable = 0;
        int priceUnavailable = 0;
        String currency = null;

        for (ProviderCallCompletedPayload event : events) {
            if (!"ACTUAL".equals(event.usageSource())) {
                usageUnavailable++;
                calls.add(unavailable(event, CostProjection.Status.USAGE_UNAVAILABLE));
                continue;
            }
            ModelPrice price = snapshot.prices().get(new ModelKey(provider, event.actualModel()));
            if (price == null) {
                priceUnavailable++;
                calls.add(unavailable(event, CostProjection.Status.PRICE_UNAVAILABLE));
                continue;
            }
            if (currency != null && !currency.equals(price.currency())) {
                throw new IllegalArgumentException("pricing snapshot contains mixed currencies");
            }
            currency = price.currency();
            long classifiedInput = (long) event.promptCacheHitTokens() + event.promptCacheMissTokens();
            long conservativeMiss = event.promptCacheMissTokens()
                + Math.max(0, event.inputTokens() - classifiedInput);
            BigDecimal hitCost = tokenCost(event.promptCacheHitTokens(), price.cacheHitInputPerMillion());
            BigDecimal missCost = tokenCost(conservativeMiss, price.cacheMissInputPerMillion());
            BigDecimal outputCost = tokenCost(event.outputTokens(), price.outputPerMillion());
            BigDecimal callTotal = hitCost.add(missCost).add(outputCost);
            total = total.add(callTotal);
            priced++;
            calls.add(new CostProjection.CallCost(event.providerCallId(), event.actualModel(),
                CostProjection.Status.PRICED, hitCost, missCost, outputCost, callTotal));
        }

        return new CostProjection(snapshot.version(), snapshot.hash(),
            currency != null ? currency : "", total, priced, usageUnavailable,
            priceUnavailable, List.copyOf(calls));
    }

    private static CostProjection.CallCost unavailable(ProviderCallCompletedPayload event,
                                                        CostProjection.Status status) {
        return new CostProjection.CallCost(event.providerCallId(), event.actualModel(), status,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static BigDecimal tokenCost(long tokens, BigDecimal perMillion) {
        return BigDecimal.valueOf(tokens).multiply(perMillion)
            .divide(ONE_MILLION, 12, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
