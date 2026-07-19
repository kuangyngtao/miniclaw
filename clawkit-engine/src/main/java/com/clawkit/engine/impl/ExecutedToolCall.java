package com.clawkit.engine.impl;

import com.clawkit.tools.ToolExecutionResult;

/**
 * executor 内部包装结果（P1-A2）。
 * 不暴露为公共 API，避免污染跨模块契约。
 */
record ExecutedToolCall(
    ToolExecutionResult result,
    int attemptCount,
    long logicalDurationMs,
    String finalStopReason
) {}
