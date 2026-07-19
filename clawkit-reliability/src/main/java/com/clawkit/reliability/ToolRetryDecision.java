package com.clawkit.reliability;

import java.time.Duration;

/**
 * 工具重试决策结果（P1-A2）。
 * 只回答一个问题：现在能否以相同输入执行下一次只读 attempt。
 */
public record ToolRetryDecision(
    boolean retrySameInput,
    Duration delay,
    String reasonCode
) {
    public static ToolRetryDecision stop(String reasonCode) {
        return new ToolRetryDecision(false, Duration.ZERO, reasonCode);
    }

    public static ToolRetryDecision retry(Duration delay, String reasonCode) {
        return new ToolRetryDecision(true, delay, reasonCode);
    }

    // ── 稳定 reasonCode ─────────────────────────────────────────────

    public static final String TRANSIENT_READ_FAILURE = "TRANSIENT_READ_FAILURE";
    public static final String NOT_RETRYABLE = "NOT_RETRYABLE";
    public static final String UNTRUSTED_METADATA = "UNTRUSTED_METADATA";
    public static final String NOT_READ_ONLY = "NOT_READ_ONLY";
    public static final String ACTION_NOT_RETRY = "ACTION_NOT_RETRY";
    public static final String ATTEMPT_LIMIT = "ATTEMPT_LIMIT";
    public static final String CONTROL_HALTED = "CONTROL_HALTED";
    public static final String DEADLINE_TOO_CLOSE = "DEADLINE_TOO_CLOSE";
}
