package com.miniclaw.tools;

/**
 * 统一返回类型 — miniclaw 内部 API 的唯一返回模型。
 * 禁止在业务代码中抛异常传递已知错误；异常仅用于 JVM 级不可恢复错误。
 */
public sealed interface Result<T> {

    record Ok<T>(T data) implements Result<T> {}

    record Err<T>(ErrorInfo error) implements Result<T> {}

    /** 错误信息 */
    record ErrorInfo(String errorCode, String message) {}
}
