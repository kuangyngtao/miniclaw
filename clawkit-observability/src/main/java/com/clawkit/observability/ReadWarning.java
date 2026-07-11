package com.clawkit.observability;

/**
 * 读取单行或整份记录时的非致命警告。
 */
public record ReadWarning(
    String file,
    long lineNumber,
    String code,
    String message
) {
    // ── 预定义 warning codes ─────────────────────────────────────────

    public static final String INVALID_JSON = "INVALID_JSON";
    public static final String UNKNOWN_EVENT_TYPE = "UNKNOWN_EVENT_TYPE";
    public static final String UNSUPPORTED_SCHEMA = "UNSUPPORTED_SCHEMA";
    public static final String CORRUPTED_SUMMARY = "CORRUPTED_SUMMARY";
    public static final String MISSING_EVENT_ID = "MISSING_EVENT_ID";
    public static final String SEQUENCE_GAP = "SEQUENCE_GAP";

    public static ReadWarning of(String file, long lineNumber, String code, String message) {
        return new ReadWarning(file, lineNumber, code, message);
    }
}
