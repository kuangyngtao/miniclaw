package com.clawkit.provider;

import com.clawkit.tools.schema.ToolCall;
import java.util.List;

/** 统一模型响应，替代 engine 直接消费 Message/ToolCall。 */
public record ModelResponse(
    String content,
    List<ToolCall> toolCalls,
    FinishReason finishReason,
    TokenUsage usage,
    ProviderResponseMetadata metadata
) {
    public ModelResponse {
        if (finishReason == null) finishReason = FinishReason.UNKNOWN;
        if (usage == null) usage = TokenUsage.EMPTY;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public static ModelResponse text(String content, TokenUsage usage) {
        return new ModelResponse(content, List.of(), FinishReason.STOP, usage, ProviderResponseMetadata.EMPTY);
    }
}
