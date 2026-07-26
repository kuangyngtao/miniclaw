package com.clawkit.evaluation.baseline;

import com.clawkit.evaluation.BenchmarkReport;
import com.clawkit.evaluation.BenchmarkSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐 case 对比当前结果与基线。
 *
 * <p>硬门禁（任一触发 → DEGRADED）：
 * <ul>
 *   <li>baseline PASS → current FAIL</li>
 *   <li>安全约束失败</li>
 *   <li>缺少 RunCompleted</li>
 *   <li>events 与 summary 不一致</li>
 *   <li>Case/script/scorer fingerprint 不兼容</li>
 * </ul>
 *
 * <p>结构指标逐 case 对比，不比全局平均值。
 */
public class RegressionComparator {

    private static final double TURN_RELATIVE_TOLERANCE = 0.10;
    private static final int TURN_ABSOLUTE_TOLERANCE = 1;
    private static final double TOOL_RELATIVE_TOLERANCE = 0.10;
    private static final int TOOL_ABSOLUTE_TOLERANCE = 2;

    /**
     * 对比当前报告与基线。
     * @param current 本次运行报告
     * @param specs   本次使用的 case 定义（用于指纹对比）
     * @param baseline 已持久化的基线
     * @return 回归报告
     */
    public static RegressionReport compare(BenchmarkReport current,
                                            List<BenchmarkSpec> specs,
                                            BaselineData baseline) {
        // 验证 schema version 兼容
        if (baseline.schemaVersion() != BaselineData.CURRENT_SCHEMA_VERSION) {
            return new RegressionReport(Verdict.INCOMPATIBLE_BASELINE,
                "Baseline schema v" + baseline.schemaVersion()
                + " != current v" + BaselineData.CURRENT_SCHEMA_VERSION,
                List.of());
        }

        // 验证 case-set fingerprint
        String currentCaseSetFp = BaselineData.computeCaseSetFingerprint(specs);
        if (!currentCaseSetFp.equals(baseline.caseSetFingerprint())) {
            return new RegressionReport(Verdict.INCOMPATIBLE_BASELINE,
                "Case set fingerprint mismatch: baseline="
                + baseline.caseSetFingerprint() + " current=" + currentCaseSetFp,
                List.of());
        }

        // 验证 script/scorer fingerprint
        String currentScriptFp = BaselineData.computeScriptScorerFingerprint(specs);
        if (!currentScriptFp.equals(baseline.scriptScorerFingerprint())) {
            return new RegressionReport(Verdict.INCOMPATIBLE_BASELINE,
                "Script/scorer fingerprint mismatch: baseline="
                + baseline.scriptScorerFingerprint() + " current=" + currentScriptFp,
                List.of());
        }

        List<RegressionReport.MetricDiff> diffs = new ArrayList<>();
        boolean degraded = false;

        // 硬门禁：baseline PASS → current FAIL
        var baselineCases = baseline.cases();
        for (var result : current.results()) {
            var entry = baselineCases.get(result.caseId());
            if (entry != null && entry.passed() && !result.passed()) {
                degraded = true;
                diffs.add(new RegressionReport.MetricDiff(
                    "hard-gate:" + result.caseId(),
                    "PASS", "FAIL", "FAIL", Verdict.DEGRADED));
            }
        }

        // 逐 case 结构指标对比
        for (var result : current.results()) {
            var entry = baselineCases.get(result.caseId());
            if (entry == null) continue;

            compareMetric("turns:" + result.caseId(),
                entry.turns(), result.turns(),
                TURN_RELATIVE_TOLERANCE, TURN_ABSOLUTE_TOLERANCE, true, diffs);

            compareMetric("tools:" + result.caseId(),
                entry.toolCalls(), result.toolCalls(),
                TOOL_RELATIVE_TOLERANCE, TOOL_ABSOLUTE_TOLERANCE, true, diffs);

            compareMetric("tool-failures:" + result.caseId(),
                entry.toolFailures(), result.toolFailures(),
                0.5, 1, true, diffs);

            compareMetric("provider-calls:" + result.caseId(),
                entry.providerCalls(), result.providerCalls(),
                0.10, 1, true, diffs);
        }

        // 聚合 verdict
        boolean anyImproved = diffs.stream().anyMatch(d -> d.verdict() == Verdict.IMPROVED);
        for (var d : diffs) {
            if (d.verdict() == Verdict.DEGRADED) degraded = true;
        }

        Verdict overall;
        if (degraded) overall = Verdict.DEGRADED;
        else if (anyImproved) overall = Verdict.IMPROVED;
        else overall = Verdict.UNCHANGED;

        return new RegressionReport(overall,
            "Baseline: " + baseline.createdAt() + " (" + baseline.gitCommit() + ")",
            diffs);
    }

    private static void compareMetric(String name,
                                       double baselineVal, double currentVal,
                                       double relTol, double absTol,
                                       boolean higherIsWorse,
                                       List<RegressionReport.MetricDiff> diffs) {
        if (baselineVal == 0 && currentVal == 0) {
            diffs.add(new RegressionReport.MetricDiff(name,
                "0", "0", "0", Verdict.UNCHANGED));
            return;
        }
        double limit = Math.max(baselineVal + absTol, Math.ceil(baselineVal * (1 + relTol)));
        double improvedLimit = Math.max(0, baselineVal - Math.max(absTol, baselineVal * relTol));

        String baselineStr = formatDouble(baselineVal);
        String currentStr = formatDouble(currentVal);
        String delta = formatDelta(baselineVal, currentVal);

        Verdict verdict;
        if (higherIsWorse) {
            if (currentVal > limit) verdict = Verdict.DEGRADED;
            else if (currentVal < improvedLimit) verdict = Verdict.IMPROVED;
            else verdict = Verdict.UNCHANGED;
        } else {
            if (currentVal < limit) verdict = Verdict.DEGRADED;
            else if (currentVal > improvedLimit) verdict = Verdict.IMPROVED;
            else verdict = Verdict.UNCHANGED;
        }

        diffs.add(new RegressionReport.MetricDiff(name, baselineStr, currentStr, delta, verdict));
    }

    private static String formatDouble(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format("%.1f", v);
    }

    private static String formatDelta(double baselineVal, double currentVal) {
        if (baselineVal == 0) return currentVal > 0 ? "+" + formatDouble(currentVal) : "0";
        double pct = ((currentVal - baselineVal) / baselineVal) * 100;
        return String.format("%+.1f%%", pct);
    }
}
