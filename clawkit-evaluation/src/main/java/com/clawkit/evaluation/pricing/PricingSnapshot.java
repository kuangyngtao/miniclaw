package com.clawkit.evaluation.pricing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Immutable, versioned pricing data used to project cost from historical usage facts. */
public record PricingSnapshot(
    String version,
    Instant effectiveAt,
    Map<ModelKey, ModelPrice> prices
) {
    public PricingSnapshot {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version must not be blank");
        if (effectiveAt == null) throw new IllegalArgumentException("effectiveAt must not be null");
        prices = Map.copyOf(prices != null ? prices : Map.of());
    }

    public String hash() {
        StringBuilder canonical = new StringBuilder(version).append('|').append(effectiveAt).append('\n');
        new TreeMap<>(prices).forEach((key, price) -> canonical
            .append(key.provider()).append('|').append(key.model()).append('|')
            .append(price.currency()).append('|')
            .append(price.cacheHitInputPerMillion().toPlainString()).append('|')
            .append(price.cacheMissInputPerMillion().toPlainString()).append('|')
            .append(price.outputPerMillion().toPlainString()).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Official DeepSeek USD list prices captured on 2026-07-22. */
    public static PricingSnapshot deepSeekV4Usd20260722() {
        Map<ModelKey, ModelPrice> prices = new LinkedHashMap<>();
        add(prices, new ModelPrice("deepseek", "deepseek-v4-flash", "USD",
            new BigDecimal("0.0028"), new BigDecimal("0.14"), new BigDecimal("0.28")));
        add(prices, new ModelPrice("deepseek", "deepseek-v4-pro", "USD",
            new BigDecimal("0.003625"), new BigDecimal("0.435"), new BigDecimal("0.87")));
        return new PricingSnapshot("deepseek-usd-2026-07-22",
            Instant.parse("2026-07-22T00:00:00Z"), prices);
    }

    private static void add(Map<ModelKey, ModelPrice> prices, ModelPrice price) {
        prices.put(price.key(), price);
    }
}
