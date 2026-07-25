package com.clawkit.evaluation.pricing;

/** Stable lookup key for a provider model price. */
public record ModelKey(String provider, String model) implements Comparable<ModelKey> {
    @Override
    public int compareTo(ModelKey other) {
        int providerOrder = provider.compareTo(other.provider);
        return providerOrder != 0 ? providerOrder : model.compareTo(other.model);
    }
}
