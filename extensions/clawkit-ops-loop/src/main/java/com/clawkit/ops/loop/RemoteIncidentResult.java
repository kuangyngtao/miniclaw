package com.clawkit.ops.loop;

import java.time.Instant;

/**
 * Aggregated result of remote discovery + diagnosis.
 *
 * <p>M2-0 Gate-0. Combines the frozen {@link DiscoveryResult} with an
 * optional {@link Diagnosis}. When the Provider is not called (discovery
 * incomplete, transport failed, or evidence insufficient), diagnosis is
 * {@code INCONCLUSIVE} and {@code providerCalled} is false.
 *
 * <p>This is the single persistence artifact — it replaces the old
 * behavior of writing only {@link DiscoveryResult}.
 */
public record RemoteIncidentResult(
    DiscoveryResult discovery,
    Diagnosis diagnosis,
    boolean providerCalled,
    String diagnosisFailureCode,
    Instant completedAt
) {
    public RemoteIncidentResult {
        if (discovery == null) throw new IllegalArgumentException("discovery required");
        if (diagnosis == null) throw new IllegalArgumentException("diagnosis required");
        if (completedAt == null) throw new IllegalArgumentException("completedAt required");
    }

    /** Convenience: was the diagnosis gated (not called)? */
    public boolean diagnosisGated() {
        return !providerCalled;
    }

    /** Convenience: is the diagnosis conclusive? */
    public boolean isConclusive() {
        return diagnosis.diagnosisStatus() == Diagnosis.DiagnosisStatus.CONFIRMED
            || diagnosis.diagnosisStatus() == Diagnosis.DiagnosisStatus.PROBABLE;
    }
}
