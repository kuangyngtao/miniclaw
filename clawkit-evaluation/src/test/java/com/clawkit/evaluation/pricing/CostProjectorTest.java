package com.clawkit.evaluation.pricing;

import com.clawkit.observability.ProviderCallCompletedPayload;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CostProjectorTest {

    @Test
    void projectsCacheAwareCostFromActualUsage() {
        var event = new ProviderCallCompletedPayload(
            "pc-1", "REACT", false, 120, 10, false, 100, 0,
            false, null, null, "deepseek-v4-flash",
            100, 20, 4, "ACTUAL");

        CostProjection result = CostProjector.project(List.of(event),
            PricingSnapshot.deepSeekV4Usd20260722(), "deepseek");

        assertThat(result.complete()).isTrue();
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.pricedCalls()).isEqualTo(1);
        assertThat(result.totalCost()).isEqualByComparingTo(new BigDecimal("0.00000588"));
        assertThat(result.calls().get(0).status()).isEqualTo(CostProjection.Status.PRICED);
    }

    @Test
    void refusesToPriceEstimatedOrUnavailableUsage() {
        var event = new ProviderCallCompletedPayload(
            "pc-1", "REACT", false, 120, 10, true, 100, 0,
            false, null, null, "deepseek-v4-flash",
            0, 0, 0, "ESTIMATED");

        CostProjection result = CostProjector.project(List.of(event),
            PricingSnapshot.deepSeekV4Usd20260722(), "deepseek");

        assertThat(result.complete()).isFalse();
        assertThat(result.pricedCalls()).isZero();
        assertThat(result.usageUnavailableCalls()).isEqualTo(1);
        assertThat(result.totalCost()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void snapshotHashIsDeterministic() {
        PricingSnapshot first = PricingSnapshot.deepSeekV4Usd20260722();
        PricingSnapshot second = PricingSnapshot.deepSeekV4Usd20260722();

        assertThat(first.hash()).isEqualTo(second.hash()).hasSize(64);
    }
}
