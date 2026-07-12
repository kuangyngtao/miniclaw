package com.clawkit.tools;

import java.time.Duration;

/**
 * 工具执行限制：超时、输出大小、截断模式和并发策略。
 */
public record ToolExecutionPolicy(
    Duration timeout,
    long maxOutputBytes,
    OutputTruncation truncation,
    ToolConcurrency concurrency
) {
    /** 默认策略：30s / 8000 字节 / HEAD 截断 / SERIAL */
    public static ToolExecutionPolicy defaults() {
        return new ToolExecutionPolicy(
            Duration.ofSeconds(30), 8000, OutputTruncation.HEAD, ToolConcurrency.SERIAL
        );
    }

    /** 只读工具的默认策略：10s / 8000 字节 / HEAD_TAIL 截断 / PARALLEL_SAFE */
    public static ToolExecutionPolicy readOnlyDefaults() {
        return new ToolExecutionPolicy(
            Duration.ofSeconds(10), 8000, OutputTruncation.HEAD_TAIL, ToolConcurrency.PARALLEL_SAFE
        );
    }

    // ── nested types ──────────────────────────────────────────────

    public enum OutputTruncation {
        /** 只保留头部 */
        HEAD,
        /** 保留头部和尾部，中段省略 */
        HEAD_TAIL,
        /** 不截断 */
        NONE
    }

    public enum ToolConcurrency {
        /** 可与其他只读工具并行 */
        PARALLEL_SAFE,
        /** 可并行但受最大并发数限制 */
        PARALLEL_LIMITED,
        /** 必须串行 */
        SERIAL
    }
}
