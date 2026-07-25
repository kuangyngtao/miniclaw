package com.clawkit.provider;

/** 模型调用参数。 */
public record ModelParameters(
    Double temperature,
    Integer maxTokens,
    Boolean stream,
    ProviderReasoningMode reasoningMode
) {
    public static final ModelParameters DEFAULT = new ModelParameters(
        null, null, false, ProviderReasoningMode.DISABLED);

    public ModelParameters(Double temperature, Integer maxTokens, Boolean stream) {
        this(temperature, maxTokens, stream, ProviderReasoningMode.DISABLED);
    }

    public ModelParameters {
        if (reasoningMode == null) reasoningMode = ProviderReasoningMode.DISABLED;
    }

    public ModelParameters withStream(boolean stream) {
        return new ModelParameters(temperature, maxTokens, stream, reasoningMode);
    }

    public ModelParameters withReasoningMode(ProviderReasoningMode reasoningMode) {
        return new ModelParameters(temperature, maxTokens, stream, reasoningMode);
    }
}
