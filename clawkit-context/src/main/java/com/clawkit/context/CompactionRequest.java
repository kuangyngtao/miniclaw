package com.clawkit.context;

import com.clawkit.tools.schema.Message;
import java.util.List;

/**
 * 压缩请求：ContextPipeline.compact() 的输入参数。
 *
 * @param modelContext  当前模型上下文消息
 * @param toolDefTokens 工具定义预估 token 数
 * @param turnCount     当前 turn 数（用于判断是否启用 MessageMasker）
 */
public record CompactionRequest(
    List<Message> modelContext,
    int toolDefTokens,
    int turnCount,
    // ── P1-A6 ────────────────────────────────────────────────────────
    CompactionHint hint,
    int reservedOutputTokens,
    int safetyMarginTokens,
    long runTokenBudgetRemaining
) {
    public CompactionRequest(
        List<Message> modelContext,
        int toolDefTokens,
        int turnCount,
        CompactionHint hint
    ) {
        this(modelContext, toolDefTokens, turnCount, hint, 0, 0, Long.MAX_VALUE);
    }

    /** 旧构造器兼容：默认 GENERAL */
    public CompactionRequest(
        List<Message> modelContext,
        int toolDefTokens,
        int turnCount
    ) {
        this(modelContext, toolDefTokens, turnCount, CompactionHint.GENERAL,
            0, 0, Long.MAX_VALUE);
    }

    public CompactionRequest {
        if (modelContext == null) throw new IllegalArgumentException("modelContext is required");
        if (toolDefTokens < 0) throw new IllegalArgumentException("toolDefTokens must be >= 0");
        if (turnCount < 0) throw new IllegalArgumentException("turnCount must be >= 0");
        if (hint == null) hint = CompactionHint.GENERAL;
        if (reservedOutputTokens < 0) {
            throw new IllegalArgumentException("reservedOutputTokens must be >= 0");
        }
        if (safetyMarginTokens < 0) {
            throw new IllegalArgumentException("safetyMarginTokens must be >= 0");
        }
        if (runTokenBudgetRemaining < 0) runTokenBudgetRemaining = 0;
    }
}
