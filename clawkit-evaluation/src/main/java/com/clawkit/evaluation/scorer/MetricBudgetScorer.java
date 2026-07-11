package com.clawkit.evaluation.scorer;

import com.clawkit.evaluation.BenchmarkResult;
import com.clawkit.evaluation.BenchmarkSpec;

import java.nio.file.Path;

/**
 * 验证各项指标不超过 case 预算上限。
 */
public class MetricBudgetScorer implements BenchmarkScorer {

    @Override
    public Score score(BenchmarkSpec spec, BenchmarkResult result, Path runArtifactDir) {
        if (result.metrics() == null) {
            return Score.notApplicable("MetricBudgetScorer");
        }

        var b = spec.budget();
        var s = result.summary();

        if (s.turns() > b.maxTurns()) {
            return Score.fail("MetricBudgetScorer",
                "turns <= " + b.maxTurns(), "turns=" + s.turns(),
                "Turn budget exceeded");
        }
        if (s.toolCalls() > b.maxToolCalls()) {
            return Score.fail("MetricBudgetScorer",
                "toolCalls <= " + b.maxToolCalls(), "toolCalls=" + s.toolCalls(),
                "Tool call budget exceeded");
        }
        if (s.toolFailures() > b.maxToolFailures()) {
            return Score.fail("MetricBudgetScorer",
                "toolFailures <= " + b.maxToolFailures(),
                "toolFailures=" + s.toolFailures(),
                "Tool failure budget exceeded");
        }
        if (s.providerCalls() > b.maxProviderCalls()) {
            return Score.fail("MetricBudgetScorer",
                "providerCalls <= " + b.maxProviderCalls(),
                "providerCalls=" + s.providerCalls(),
                "Provider call budget exceeded");
        }
        if (s.compactCount() > b.maxCompactions()) {
            return Score.fail("MetricBudgetScorer",
                "compactions <= " + b.maxCompactions(),
                "compactions=" + s.compactCount(),
                "Compaction budget exceeded");
        }

        return Score.pass("MetricBudgetScorer", 1.0,
            "turns=" + s.turns() + " tools=" + s.toolCalls()
            + " provider=" + s.providerCalls());
    }
}
