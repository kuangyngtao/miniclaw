package com.clawkit.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * P0-5：compact 管线的不可变 anchor sidecar。
 *
 * <p>包含 canonical rendered text、required id 集合和 hash。
 * 验证不通过扫描消息中的 id= 文本完成，避免工具输出伪造 anchor。
 */
public record AnchorSnapshot(
    String renderedText,
    List<String> requiredIds,
    String sha256
) {
    public static final AnchorSnapshot EMPTY = new AnchorSnapshot("", List.of(), "");

    public AnchorSnapshot {
        if (renderedText == null) renderedText = "";
        if (requiredIds == null) requiredIds = List.of();
        requiredIds = List.copyOf(requiredIds);
        if (sha256 == null) sha256 = "";
    }

    /**
     * P0-5：canonical rendering — 防止 summary 中的换行、id=、system-like 文本伪造结构。
     */
    public static AnchorSnapshot render(
            CompactionHint hint,
            int maxCodePoints,
            int maxAnchors) {

        if (hint == null || hint.anchors().isEmpty()) {
            return EMPTY;
        }

        var anchors = hint.anchors();
        var sb = new StringBuilder(512);
        sb.append("[Runtime][Compaction Anchors]\n");
        sb.append("profile=").append(hint.profile().name()).append("\n");

        int count = 0;
        var requiredIds = new java.util.ArrayList<String>();
        for (var a : anchors) {
            if (count >= maxAnchors) break;
            count++;

            // canonical escaping: 换行→空格, id= 前缀保护
            String escaped = a.summary()
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("id=", "id\\=");
            if (escaped.codePointCount(0, escaped.length()) > maxCodePoints) {
                int end = escaped.offsetByCodePoints(0, maxCodePoints);
                escaped = escaped.substring(0, end) + "…";
            }

            sb.append("- id=").append(a.id())
              .append(" kind=").append(a.kind().name())
              .append(" state=").append(a.state())
              .append(" required=").append(a.required())
              .append(" provenance=").append(a.provenance().name());
            if (a.observedAt() != null) {
                sb.append(" observedAt=").append(a.observedAt().toString());
            }
            sb.append("\n  summary=").append(escaped);
            if (a.evidenceRef() != null && !a.evidenceRef().isBlank()) {
                sb.append("\n  evidenceRef=").append(a.evidenceRef());
            }
            sb.append("\n");

            if (a.required()) {
                requiredIds.add(a.id());
            }
        }

        String text = sb.toString();
        String hash = sha256(text);
        return new AnchorSnapshot(text, List.copyOf(requiredIds), hash);
    }

    /** 验证 renderedText 的完整性：重新计算 hash 并与 stored sha256 比较 */
    public boolean verify() {
        if (sha256.isEmpty() && renderedText.isEmpty()) return true;
        return sha256.equals(sha256(renderedText));
    }

    /** 检查所有 required id 都出现在 rendered text 中 */
    public List<String> findMissingRequired() {
        if (requiredIds.isEmpty()) return List.of();
        return requiredIds.stream()
            .filter(id -> !renderedText.contains("id=" + id))
            .toList();
    }

    private static String sha256(String input) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
