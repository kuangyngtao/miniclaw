package com.clawkit.evaluation.scorer;

import com.clawkit.evaluation.BenchmarkResult;
import com.clawkit.evaluation.BenchmarkSpec;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 对单个 benchmark case 结果评分。
 *
 * <p>每个 scorer 必须通过 {@link #descriptor()} 提供稳定的身份和配置指纹，
 * 用于 baseline fingerprint。lambda 或匿名类无法提供稳定描述，基线生成时会拒绝。
 */
@FunctionalInterface
public interface BenchmarkScorer {
    Score score(BenchmarkSpec spec, BenchmarkResult result, Path runArtifactDir);

    /**
     * 稳定描述符，参与 baseline fingerprint。
     * 默认实现检测 lambda / 匿名类并拒绝。
     */
    default ScorerDescriptor descriptor() {
        Class<?> c = getClass();
        if (c.isSynthetic() || c.isAnonymousClass() || c.getSimpleName().isEmpty()) {
            throw new IllegalStateException(
                "Scorer " + c.getName() + " is a lambda or anonymous class — "
                + "override descriptor() to provide a stable identity for baseline fingerprinting");
        }
        return new ScorerDescriptor(c.getSimpleName(), 1, "");
    }

    /** Scorer 稳定身份与配置指纹，仅参与 SHA-256，不单独持久化。 */
    record ScorerDescriptor(String stableId, int version, String canonicalConfig) {
        public String fingerprint() {
            String raw = stableId + "|v" + version + "|" + canonicalConfig;
            return sha256(raw);
        }

        private static String sha256(String input) {
            try {
                var md = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
