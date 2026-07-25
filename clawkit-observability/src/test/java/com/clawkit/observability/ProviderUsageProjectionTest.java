package com.clawkit.observability;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderUsageProjectionTest {

    @Test
    void aggregatesUsageBreakdownAndSourceCoverage() {
        RunAccumulator accumulator = new RunAccumulator("run-1");
        accumulator.accept(envelope(new ProviderCallCompletedPayload(
            "pc-1", "REACT", false, 120, 30, false, 10, 0,
            false, null, null, "deepseek-v4-flash",
            80, 40, 18, "ACTUAL"), 1));
        accumulator.accept(envelope(new ProviderCallCompletedPayload(
            "pc-2", "REACT", false, 20, 5, true, 10, 0,
            false, null, null, "deepseek-v4-flash",
            0, 20, 0, "ESTIMATED"), 2));

        RunMetrics.ProviderMetrics metrics = accumulator.metrics().provider();
        assertThat(metrics.calls()).isEqualTo(2);
        assertThat(metrics.promptCacheHitTokens()).isEqualTo(80);
        assertThat(metrics.promptCacheMissTokens()).isEqualTo(60);
        assertThat(metrics.reasoningTokens()).isEqualTo(18);
        assertThat(metrics.actualUsageCalls()).isEqualTo(1);
        assertThat(metrics.estimatedUsageCalls()).isEqualTo(1);
        assertThat(metrics.unavailableUsageCalls()).isZero();
    }

    @Test
    void warnsOnDuplicateProviderCallId() {
        RunAccumulator accumulator = new RunAccumulator("run-1");
        var payload = new ProviderCallCompletedPayload(
            "pc-1", "REACT", false, 1, 1, false, 10, 0,
            false, null, null, "deepseek-v4-flash",
            0, 1, 0, "ACTUAL");
        accumulator.accept(envelope(payload, 1));
        accumulator.accept(envelope(payload, 2));

        assertThat(accumulator.metrics().warnings())
            .contains("duplicate providerCallId: pc-1");
    }

    private static RunEventEnvelope envelope(RunEventPayload payload, long sequence) {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        return new RunEventEnvelope(1, "evt-" + sequence,
            RunEventCodec.eventTypeFor(payload.getClass()), sequence,
            now, now, "run-1", null, 1, payload);
    }
}
