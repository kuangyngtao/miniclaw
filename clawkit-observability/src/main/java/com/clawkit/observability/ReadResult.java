package com.clawkit.observability;

import java.util.List;

/**
 * 读取结果，包含数据和警告列表。
 * 部分行损坏不影响其他行的读取。
 */
public record ReadResult<T>(
    T value,
    List<ReadWarning> warnings
) {
    public static <T> ReadResult<T> of(T value, List<ReadWarning> warnings) {
        return new ReadResult<>(value, warnings != null ? List.copyOf(warnings) : List.of());
    }

    public static <T> ReadResult<T> empty(List<ReadWarning> warnings) {
        return new ReadResult<>(null, warnings != null ? List.copyOf(warnings) : List.of());
    }
}
