package com.clawkit.engine;

/** Session 加载/保存的结构化错误。 */
public enum SessionError {
    UNSUPPORTED_VERSION,
    CORRUPTED_JSON,
    NOT_FOUND,
    IO_ERROR
}
