package com.clawkit.observability.model;

import com.clawkit.observability.RunStatus;

import java.time.Instant;

/**
 * 单次 run 的汇总指标，写入 summary.json。
 */
public record RunMetrics(
    String runId,
    String taskSummary,
    Instant startTime,
    Instant endTime,
    long durationMs,
    RunStatus status,
    String failureType,
    String errorCode,
    String errorMessage,
    int turns,
    int toolCalls,
    int toolFailures,
    int compactCount,
    String permissionMode,
    String thinkingMode,
    String executionMode,
    String workDir,
    String model
) {}
