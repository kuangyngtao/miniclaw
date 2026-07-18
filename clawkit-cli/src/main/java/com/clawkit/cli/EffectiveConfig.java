package com.clawkit.cli;

import java.nio.file.Path;
import java.util.Map;

/** Non-secret effective configuration. Safe to render and log. */
public record EffectiveConfig(
    String model,
    String baseUrl,
    String protocol,
    int requestTimeoutSeconds,
    int maxRetries,
    boolean thinking,
    Path rootDir,
    boolean apiKeyConfigured,
    Map<String, String> sources
) {
    public EffectiveConfig {
        sources = Map.copyOf(sources);
    }
}
