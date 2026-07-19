package com.clawkit.tools;

import java.nio.charset.StandardCharsets;

/**
 * 原子输出结果（P1-A4）：消除 output、outputBytes、truncated、stats、envelope 漂移。
 *
 * <p>构造时校验不变量：
 * <ul>
 *   <li>stats.totalBytes == envelope.totalBytes</li>
 *   <li>stats.retainedSourceBytes == envelope.returnedBytes</li>
 *   <li>stats.returnedBytes == UTF8(text).length</li>
 *   <li>stats.inputComplete == envelope.inputComplete</li>
 *   <li>envelope.returnedBytes + omittedBytes == totalBytes</li>
 * </ul>
 */
public record ReducedToolOutput(
    String text,
    OutputEnvelope envelope,
    ToolOutputStats stats
) {
    public ReducedToolOutput {
        if (text == null) text = "";
        if (stats.totalBytes() != envelope.totalBytes()) {
            throw new IllegalArgumentException(
                "stats.totalBytes=" + stats.totalBytes()
                + " != envelope.totalBytes=" + envelope.totalBytes());
        }
        if (stats.retainedSourceBytes() != envelope.returnedBytes()) {
            throw new IllegalArgumentException(
                "stats.retainedSourceBytes=" + stats.retainedSourceBytes()
                + " != envelope.returnedBytes=" + envelope.returnedBytes());
        }
        int textBytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (stats.returnedBytes() != textBytes) {
            throw new IllegalArgumentException(
                "stats.returnedBytes=" + stats.returnedBytes()
                + " != UTF8(text).length=" + textBytes);
        }
        if (stats.inputComplete() != envelope.inputComplete()) {
            throw new IllegalArgumentException(
                "stats.inputComplete=" + stats.inputComplete()
                + " != envelope.inputComplete=" + envelope.inputComplete());
        }
    }
}
