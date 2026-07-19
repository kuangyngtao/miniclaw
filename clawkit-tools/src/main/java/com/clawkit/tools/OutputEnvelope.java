package com.clawkit.tools;

import java.util.List;

/**
 * 输出信封：截断保真的统一输出模型（P1-G0 契约）。
 *
 * <p>流式采集时同时维护有界 head、有界 tail 环形缓冲、错误匹配片段、
 * 总字节数和 hash。完整原始输出不默认持久化；模型只获得摘要与
 * {@code evidenceRefs} 引用。
 */
public record OutputEnvelope(
    String head,
    String tail,
    List<String> errorExcerpts,
    long totalBytes,
    long returnedBytes,
    long omittedBytes,
    String truncationReason,
    String sha256,
    List<String> evidenceRefs,
    boolean redactionApplied,
    String encoding
) {
    public OutputEnvelope {
        if (head == null) head = "";
        if (tail == null) tail = "";
        errorExcerpts = errorExcerpts == null ? List.of() : List.copyOf(errorExcerpts);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        if (encoding == null || encoding.isBlank()) encoding = "UTF-8";
        if (totalBytes < 0 || returnedBytes < 0 || omittedBytes < 0) {
            throw new IllegalArgumentException("byte counts must be >= 0");
        }
        if (returnedBytes + omittedBytes != totalBytes) {
            throw new IllegalArgumentException(
                "returnedBytes + omittedBytes must equal totalBytes");
        }
    }

    /** 是否发生截断。 */
    public boolean truncated() {
        return omittedBytes > 0;
    }

    /** 未截断的完整输出信封。 */
    public static OutputEnvelope complete(String output) {
        String text = output != null ? output : "";
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new OutputEnvelope(text, "", List.of(), bytes.length, bytes.length, 0,
            null, com.clawkit.tools.action.Digests.sha256Hex(bytes), List.of(), false, "UTF-8");
    }
}
