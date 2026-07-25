package com.clawkit.observability;

/** Provider 调用启动标记事件。 */
public record ProviderCallStartedPayload(
    String providerCallId,
    String phase,
    boolean streaming,
    String routeId,
    String routeReasonCode,
    String providerDialect,
    String requestedModel,
    String reasoningMode,
    String promptFingerprint
) implements RunEventPayload {
    public ProviderCallStartedPayload(String providerCallId, String phase, boolean streaming) {
        this(providerCallId, phase, streaming, null, null, null, null, null, null);
    }
}
