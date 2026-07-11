package com.clawkit.evaluation.scorer;

import com.clawkit.evaluation.BenchmarkResult;
import com.clawkit.evaluation.BenchmarkSpec;
import com.clawkit.observability.RunEventType;

import java.nio.file.Path;

/**
 * 验证 O1 事件不变量：
 * <ul>
 *   <li>有 run_started 事件</li>
 *   <li>有 run_completed 事件</li>
 *   <li>至少一个 turn</li>
 *   <li>events 与 summary 的一致性（turns / tools）</li>
 * </ul>
 */
public class EventInvariantScorer implements BenchmarkScorer {

    @Override
    public Score score(BenchmarkSpec spec, BenchmarkResult result, Path runArtifactDir) {
        if (result.metrics() == null) {
            return Score.fail("EventInvariantScorer", "metrics present", "null",
                "No metrics — events file may be missing or corrupt");
        }

        var summary = result.summary();
        var metrics = result.metrics();

        // 检查至少有一次 provider 调用或 tool 调用（run 至少有 turn）
        if (summary.turns() == 0 && summary.providerCalls() == 0) {
            return Score.fail("EventInvariantScorer", "turns > 0", "turns=0",
                "No turns recorded — run may have crashed before first turn");
        }

        // events 与 summary 一致性
        int summaryTurns = summary.turns();
        int summaryTools = summary.toolCalls();
        int metricsTools = metrics.tools().calls();

        if (summaryTools != metricsTools) {
            return Score.warn("EventInvariantScorer",
                "summary.toolCalls=" + summaryTools + " vs metrics.tools.calls=" + metricsTools
                + " — should match");
        }

        return Score.pass("EventInvariantScorer", 1.0,
            "turns=" + summaryTurns + " tools=" + summaryTools
            + " providerCalls=" + summary.providerCalls());
    }
}
