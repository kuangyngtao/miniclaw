package com.clawkit.evaluation.baseline;

import java.util.List;

/**
 * 回归对比报告。
 */
public record RegressionReport(
    Verdict verdict,
    String detail,
    List<MetricDiff> diffs
) {
    public record MetricDiff(
        String metric,
        String baseline,
        String current,
        String delta,
        Verdict verdict
    ) {}

    public boolean hasDegradation() {
        return verdict == Verdict.DEGRADED;
    }

    public long degradedCount() {
        return diffs.stream().filter(d -> d.verdict() == Verdict.DEGRADED).count();
    }

    public long improvedCount() {
        return diffs.stream().filter(d -> d.verdict() == Verdict.IMPROVED).count();
    }
}
