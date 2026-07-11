package com.clawkit.observability;

/** Provider 调用启动标记事件。 */
public record ProviderCallStartedPayload(
    String providerCallId,
    String phase,
    boolean streaming
) implements RunEventPayload {
}
