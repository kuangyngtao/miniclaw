package com.clawkit.provider;

/** 模型调用参数。 */
public record ModelParameters(
    Double temperature,
    Integer maxTokens,
    Boolean stream
) {
    public static final ModelParameters DEFAULT = new ModelParameters(null, null, false);

    public ModelParameters withStream(boolean stream) {
        return new ModelParameters(temperature, maxTokens, stream);
    }
}
