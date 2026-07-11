package com.clawkit.evaluation;

import java.util.List;

/**
 * 聚合的 benchmark 报告，包含所有 case 结果和汇总指标。
 */
public record BenchmarkReport(
    String evaluationId,
    String clawkitVersion,
    String gitCommit,
    long startedAtMs,
    long durationMs,
    List<BenchmarkResult> results,
    String verdict,
    String baselineId
) {
    public int totalCases() {
        return results.size();
    }

    public int passed() {
        return (int) results.stream().filter(BenchmarkResult::passed).count();
    }

    public int failed() {
        return totalCases() - passed();
    }

    public double passRate() {
        return totalCases() == 0 ? 0.0 : (double) passed() / totalCases();
    }

    public double avgTurns() {
        return results.stream().mapToInt(BenchmarkResult::turns).average().orElse(0);
    }

    public double avgToolCalls() {
        return results.stream().mapToInt(BenchmarkResult::toolCalls).average().orElse(0);
    }

    public double avgDurationMs() {
        return results.stream().mapToLong(BenchmarkResult::durationMs).average().orElse(0);
    }

    public double avgToolFailures() {
        return results.stream().mapToInt(BenchmarkResult::toolFailures).average().orElse(0);
    }

    public double toolFailureRate() {
        int total = results.stream().mapToInt(BenchmarkResult::toolCalls).sum();
        int failures = results.stream().mapToInt(BenchmarkResult::toolFailures).sum();
        return total == 0 ? 0 : (double) failures / total;
    }

    public double avgProviderCalls() {
        return results.stream().mapToInt(BenchmarkResult::providerCalls).average().orElse(0);
    }

    public double avgProviderRetries() {
        return results.stream().mapToInt(BenchmarkResult::providerRetries).average().orElse(0);
    }

    public int totalCompactions() {
        return results.stream().mapToInt(BenchmarkResult::compactCount).sum();
    }

    public double avgInputTokens() {
        return results.stream().mapToLong(BenchmarkResult::inputTokens).average().orElse(0);
    }

    public double avgOutputTokens() {
        return results.stream().mapToLong(BenchmarkResult::outputTokens).average().orElse(0);
    }
}
