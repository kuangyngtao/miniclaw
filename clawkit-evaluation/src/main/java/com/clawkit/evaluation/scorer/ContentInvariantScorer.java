package com.clawkit.evaluation.scorer;

import com.clawkit.evaluation.BenchmarkResult;
import com.clawkit.evaluation.BenchmarkSpec;
import com.clawkit.observability.CompactCompletedPayload;
import com.clawkit.observability.ProviderCallStartedPayload;
import com.clawkit.observability.RunEventEnvelope;
import com.clawkit.observability.RunReader;
import com.clawkit.observability.ToolCompletedPayload;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
            if (!evaluate(condition, events)) {
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

    private boolean evaluate(String condition, List<RunEventEnvelope> events) {
        List<ToolCompletedPayload> toolResults = events.stream()
            .map(RunEventEnvelope::payload)
            .filter(ToolCompletedPayload.class::isInstance)
            .map(ToolCompletedPayload.class::cast)
            .toList();

        // P0-6: NoDuplicateToolResult — 每个 toolCallId 恰好一个最终结果
        if (condition.equals("NoDuplicateToolResult")) {
            if (toolResults.isEmpty()) {
                return false;
            }
            Set<String> ids = toolResults.stream()
                .map(ToolCompletedPayload::toolCallId)
                .collect(Collectors.toSet());
            return ids.size() == toolResults.size();
        }

        if (condition.startsWith("NoDuplicateToolResult:")) {
            String toolCallId = condition.substring("NoDuplicateToolResult:".length());
            return !toolCallId.isBlank() && toolResults.stream()
                .filter(p -> toolCallId.equals(p.toolCallId()))
                .count() == 1;
        }

        // P0-6: MaxAttempts — 检查最终事件中的真实 attemptCount
        if (condition.startsWith("MaxAttempts:")) {
            try {
                int max = Integer.parseInt(condition.substring("MaxAttempts:".length()));
                return max > 0 && !toolResults.isEmpty() && toolResults.stream()
                    .allMatch(p -> p.attemptCount() >= 1 && p.attemptCount() <= max);
            } catch (NumberFormatException e) {
                return false;
            }
        }

        // P0-6: NoProviderCallAfterCompactFailure
        if (condition.equals("NoProviderCallAfterCompactFailure")) {
            long firstFailureSequence = events.stream()
                .filter(e -> e.payload() instanceof CompactCompletedPayload p && p.failed())
                .mapToLong(RunEventEnvelope::sequence)
                .min()
                .orElse(Long.MAX_VALUE);
            if (firstFailureSequence == Long.MAX_VALUE) {
                return true;
            }
            return events.stream().noneMatch(e ->
                e.sequence() > firstFailureSequence
                    && e.payload() instanceof ProviderCallStartedPayload);
        }

        // P0-6: ContainsAnchor — 只接受 compact 审计中的结构化 retained ID
        if (condition.startsWith("ContainsAnchor:")) {
            String id = condition.substring("ContainsAnchor:".length());
            return !id.isBlank() && events.stream().anyMatch(e ->
                e.payload() instanceof CompactCompletedPayload p
                    && p.retainedAnchorIds() != null
                    && p.retainedAnchorIds().contains(id));
        }

        if (condition.startsWith("ContainsEvidenceRef:")) {
            String ref = condition.substring("ContainsEvidenceRef:".length());
            return !ref.isBlank() && events.stream()
                .anyMatch(e -> e.payload().toString().contains(ref));
        }

        // 未注册 invariant 必须失败，避免拼写错误或未实现检查静默通过。
        return false;
    }
}
