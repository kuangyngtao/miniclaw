package com.clawkit.tools;

import java.nio.charset.StandardCharsets;

/**
 * 工具输出统计（P1-A4 扩展）。
 *
 * <p>字节数按 UTF-8 编码后的真实字节计算。
 *
 * <p>定义：
 * <ul>
 *   <li>totalBytes：观察到的源 bytes</li>
 *   <li>returnedBytes：模型实际看到的最终 UTF-8 bytes（含渲染标签）</li>
 *   <li>retainedSourceBytes：被 reducer 代表的脱敏前源 bytes</li>
 *   <li>totalLines / returnedLines：-1 表示未知</li>
 *   <li>inputComplete=false：提前停止扫描，total* 只是已观察值</li>
 * </ul>
 */
public record ToolOutputStats(
    long totalBytes,
    long returnedBytes,
    long retainedSourceBytes,
    long totalLines,
    long returnedLines,
    boolean truncated,
    String truncationReason,
    String retentionPolicy,
    boolean inputComplete
) {
    /** V0 兼容构造器（3 参数 → LEGACY_V0） */
    public ToolOutputStats(long totalBytes, long returnedBytes, boolean truncated) {
        this(totalBytes, returnedBytes,
            Math.min(returnedBytes, totalBytes),  // retainedSourceBytes
            -1, -1,                                // lines unknown
            truncated,
            truncated ? "MAX_OUTPUT_BYTES" : null,
            "LEGACY_V0",
            !truncated);                           // inputComplete
    }

    /** 从输出字符串创建统计（LEGACY_V0 兼容） */
    public static ToolOutputStats fromOutput(String output, boolean truncated) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        return new ToolOutputStats(bytes.length, bytes.length, truncated);
    }

    /** 从输出字符串创建截断统计（LEGACY_V0 兼容） */
    public static ToolOutputStats truncated(String returned, long totalBytes) {
        byte[] bytes = returned.getBytes(StandardCharsets.UTF_8);
        return new ToolOutputStats(totalBytes, bytes.length, true);
    }

    public static final ToolOutputStats EMPTY = new ToolOutputStats(0, 0, false);
}
