package com.clawkit.cli;

/** Effective non-secret settings plus an in-memory credential. Never log this object. */
final class ResolvedConfiguration {
    private final EffectiveConfig effective;
    private final String apiKey;

    ResolvedConfiguration(EffectiveConfig effective, String apiKey) {
        this.effective = effective;
        this.apiKey = apiKey;
    }

    EffectiveConfig effective() { return effective; }
    String apiKey() { return apiKey; }

    @Override public String toString() {
        return "ResolvedConfiguration[effective=" + effective + ", apiKey=***]";
    }
}
