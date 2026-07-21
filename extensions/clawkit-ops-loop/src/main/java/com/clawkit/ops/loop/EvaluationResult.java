package com.clawkit.ops.loop;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EvaluationResult(
    boolean passed,
    Instant evaluatedAt,
    Map<String, Boolean> assertions,
    List<String> failures,
    List<String> vetoes
) {
    public EvaluationResult {
        assertions = Map.copyOf(assertions);
        failures = List.copyOf(failures);
        vetoes = List.copyOf(vetoes);
    }
}
