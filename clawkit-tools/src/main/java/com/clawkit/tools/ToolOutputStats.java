package com.clawkit.tools;

import java.nio.charset.StandardCharsets;

/**
 * 工具输出统计。
 * 字节数按 UTF-8 编码后的真实字节计算，不使用 String.length()。
 */
public record ToolOutputStats(
    long totalBytes,
    long returnedBytes,
    boolean truncated
) {
    /** 从输出字符串创建统计 */
    public static ToolOutputStats fromOutput(String output, boolean truncated) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        return new ToolOutputStats(bytes.length, bytes.length, truncated);
    }

    /** 从输出字符串创建统计（total != returned，如截断场景） */
    public static ToolOutputStats truncated(String returned, long totalBytes) {
        byte[] bytes = returned.getBytes(StandardCharsets.UTF_8);
        return new ToolOutputStats(totalBytes, bytes.length, true);
    }

    public static final ToolOutputStats EMPTY = new ToolOutputStats(0, 0, false);
}
