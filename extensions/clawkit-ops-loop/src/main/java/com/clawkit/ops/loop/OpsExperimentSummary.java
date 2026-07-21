package com.clawkit.ops.loop;

import java.time.Instant;
import java.util.List;

public record OpsExperimentSummary(
    Instant startedAt,
    Instant completedAt,
    int requestedRuns,
    int passedRuns,
    boolean initialStateConsistent,
    boolean cleanupConsistent,
    List<RunOutcome> runs
) {
    public OpsExperimentSummary {
        runs = List.copyOf(runs);
    }

    public boolean passed() {
        return passedRuns == requestedRuns
            && initialStateConsistent && cleanupConsistent;
    }

    public record RunOutcome(
        int iteration,
        String runId,
        boolean initialStateHealthy,
        boolean cleanupComplete,
        EvaluationResult evaluation,
        String outputDirectory,
        String error
    ) {}
}
