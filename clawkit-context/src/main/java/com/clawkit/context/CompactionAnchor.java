package com.clawkit.context;

import java.time.Instant;
import java.util.Objects;

/**
 * P1-A6：compaction anchor — 长任务 compact 后必须保留的最小充分状态。
 *
 * <p>验证规则：
 * <ul>
 *   <li>id 只含 [a-zA-Z0-9._-]，长度 1-64</li>
 *   <li>summary 不超过 512 Unicode code points</li>
 *   <li>非 USER 来源的 CONFIRMED_FACT/COUNTER_EVIDENCE 必须有 evidenceRef</li>
 *   <li>MODEL_DERIVED 只能用于 OPEN_HYPOTHESIS</li>
 * </ul>
 */
public record CompactionAnchor(
    String id,
    AnchorKind kind,
    String summary,
    String evidenceRef,
    boolean required,
    String state,
    AnchorProvenance provenance,
    Instant observedAt
) {
    private static final String ID_PATTERN = "[a-zA-Z0-9._-]{1,64}";

    public CompactionAnchor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        if (summary == null) summary = "";
        if (state == null) state = "OPEN";
        if (provenance == null) provenance = AnchorProvenance.USER;
        if (observedAt == null) observedAt = Instant.now();

        if (!id.matches(ID_PATTERN)) {
            throw new IllegalArgumentException("invalid anchor id: " + id);
        }
        if (summary.codePointCount(0, summary.length()) > 512) {
            throw new IllegalArgumentException("summary exceeds 512 code points");
        }
        if (provenance != AnchorProvenance.USER
            && (kind == AnchorKind.CONFIRMED_FACT || kind == AnchorKind.COUNTER_EVIDENCE)
            && (evidenceRef == null || evidenceRef.isBlank())) {
            throw new IllegalArgumentException(
                kind + " with provenance " + provenance + " requires evidenceRef");
        }
        if (provenance == AnchorProvenance.MODEL_DERIVED
            && kind != AnchorKind.OPEN_HYPOTHESIS) {
            throw new IllegalArgumentException(
                "MODEL_DERIVED only allowed for OPEN_HYPOTHESIS, got " + kind);
        }
    }

    /** 稳定状态值 */
    public static final String OPEN = "OPEN";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String REFUTED = "REFUTED";
    public static final String DONE = "DONE";
}
