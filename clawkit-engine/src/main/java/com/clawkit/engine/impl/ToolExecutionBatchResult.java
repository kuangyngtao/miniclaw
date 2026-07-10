package com.clawkit.engine.impl;

import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.schema.Message;

import java.util.List;

/**
 * 批量工具执行结果，包含各工具结果和上下文消息。
 */
public record ToolExecutionBatchResult(
    List<ToolExecutionResult> results,
    List<Message> toolResultMessages
) {}
