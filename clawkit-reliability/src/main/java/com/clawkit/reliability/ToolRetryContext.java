package com.clawkit.reliability;

import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolMetadata;

import java.time.Duration;

/**
 * 工具重试决策的只读输入（P1-A2）。
 *
 * <p>由 ToolCallExecutor 在每次 attempt 后构造；决策层不持有可变状态。
 */
public record ToolRetryContext(
    ToolExecutionResult lastResult,
    ToolMetadata metadata,
    int attemptsMade,
    int maxAttempts,
    Duration elapsed,
    Duration remaining
) {}
