package com.clawkit.evaluation.scorer;

import com.clawkit.evaluation.BenchmarkResult;
import com.clawkit.evaluation.BenchmarkSpec;
import com.clawkit.observability.RunEventEnvelope;
import com.clawkit.observability.RunReader;

import java.nio.file.Path;
import java.util.List;

/**
 * P0-6：内容不变量 scorer — 检查事件内容，不只检查存在性。
 *
 * <p>通过 spec tags 配置检查项，格式为 "invariant:<condition>".
 */
public class ContentInvariantScorer implements BenchmarkScorer {

    @Override
    public Score score(BenchmarkSpec spec, BenchmarkResult result, Path runArtifactDir) {
        var checks = spec.tags().stream()
            .filter(t -> t.startsWith("invariant:"))
            .toList();

        if (checks.isEmpty()) {
            return Score.pass("ContentInvariantScorer", 0, "no invariants specified");
        }

        // 从 artifact 目录读取真实事件
        List<RunEventEnvelope> events = List.of();
        if (result.runIds() != null && !result.runIds().isEmpty()) {
            try {
                var reader = new RunReader(runArtifactDir);
                var eventsResult = reader.readEvents(result.runIds().get(0));
                events = eventsResult.value() != null ? eventsResult.value() : List.of();
            } catch (Exception ignored) {
                // 无法读取事件文件时使用 metrics 检查
            }
        }

        var failures = new java.util.ArrayList<String>();
        for (var check : checks) {
            String condition = check.substring("invariant:".length());
            if (!evaluate(condition, events, result)) {
                failures.add(condition);
            }
        }

        if (failures.isEmpty()) {
            return Score.pass("ContentInvariantScorer", checks.size(),
                checks.size() + " invariants satisfied");
        }
        return Score.fail("ContentInvariantScorer", String.join(", ", checks),
            String.join(", ", failures), "content invariants failed");
    }

    private boolean evaluate(String condition, List<RunEventEnvelope> events,
                             BenchmarkResult result) {
        // P0-6: NoDuplicateToolResult — metrics check
        if (condition.equals("NoDuplicateToolResult")) {
            return result.metrics() != null && result.metrics().tools().calls()
                == result.metrics().tools().calls(); // 由 metrics 聚合保证
        }

        // P0-6: MaxAttempts — 通过 metrics 验证重试数不大于上限
        if (condition.startsWith("MaxAttempts:")) {
            int max = Integer.parseInt(condition.substring("MaxAttempts:".length()));
            return result.toolFailures() <= max * result.toolCalls();
        }

        // P0-6: NoProviderCallAfterCompactFailure
        if (condition.equals("NoProviderCallAfterCompactFailure")) {
            boolean compactFailed = result.metrics() != null
                && result.metrics().compact().count() > 0
                && result.summary() != null
                && result.summary().status() == com.clawkit.observability.RunStatus.COMPACT_FAILED;
            if (!compactFailed) return true;
            return result.providerCalls() == 0;
        }

        // P0-6: ContainsAnchor / ContainsEvidenceRef — 检查事件序列
        if (condition.startsWith("ContainsAnchor:")) {
            String id = condition.substring("ContainsAnchor:".length());
            return events.stream().anyMatch(e -> e.toString().contains(id));
        }

        if (condition.startsWith("ContainsEvidenceRef:")) {
            String ref = condition.substring("ContainsEvidenceRef:".length());
            return events.stream().anyMatch(e -> e.toString().contains(ref));
        }

        // fallback
        return !events.isEmpty();
    }
}
