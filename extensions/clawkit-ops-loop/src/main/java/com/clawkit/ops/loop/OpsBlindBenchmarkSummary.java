package com.clawkit.ops.loop;

import java.time.Instant;
import java.util.List;

public record OpsBlindBenchmarkSummary(
    String schemaVersion,
    Instant startedAt,
    Instant completedAt,
    int requestedRuns,
    int completedRuns,
    int passedRuns,
    List<RunOutcome> outcomes
) {
    public OpsBlindBenchmarkSummary { outcomes = List.copyOf(outcomes); }

    public record RunOutcome(
        String caseName,
        int iteration,
        String incidentId,
        String status,
        boolean fixtureHealthy,
        boolean cleanupComplete,
        EvaluationResult evaluation,
        String outputDirectory,
        String error
    ) { }
}
