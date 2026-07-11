package com.clawkit.observability;

import java.time.Instant;
import java.util.List;

/**
 * 单次 run 的汇总快照，写入 summary.json。
 * 所有字段从 events 聚合生成，与 RunAccumulator 使用同一套计算规则。
 */
public record RunSummary(
    int schemaVersion,
    String runId,
    String parentRunId,
    String taskSummary,

    RunStatus status,
    boolean finalized,

    Instant startTime,
    Instant endTime,
    long durationMs,

    int turns,

    int providerCalls,
    int providerFailures,
    int providerRetries,
    long providerDurationMs,
    long inputTokens,
    long outputTokens,
    boolean tokensEstimated,

    int toolCalls,
    int toolFailures,
    int lowRiskToolCalls,
    int mediumRiskToolCalls,
    int highRiskToolCalls,

    int approvalRequested,
    int approvalApproved,
    int approvalRejected,
    int approvalModified,

    int contextPeakTokens,
    int contextWindow,
    int compactCount,
    int compactFailures,

    String permissionMode,
    String thinkingMode,
    String executionMode,
    String workDir,
    String model,

    String errorCode,
    String errorMessage,
    List<String> warnings
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** 创建 RUNNING 状态的初始 summary */
    public static RunSummary initial(String runId, String parentRunId) {
        return new RunSummary(
            CURRENT_SCHEMA_VERSION, runId, parentRunId, "",
            RunStatus.RUNNING, false,
            null, null, 0,
            0,
            0, 0, 0, 0, 0, 0, false,
            0, 0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            "", "", "", "", "",
            null, null, List.of()
        );
    }
}
