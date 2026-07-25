package com.clawkit.provider;

/** Non-secret provider identity used by observability. */
public record ProviderDescriptor(ProviderDialect dialect, String requestedModel) {
    public static final ProviderDescriptor UNKNOWN = new ProviderDescriptor(null, null);
}
