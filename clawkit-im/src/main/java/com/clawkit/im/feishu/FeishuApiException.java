package com.clawkit.im.feishu;

import java.io.IOException;

/**
 * Structured Feishu API error with retry classification.
 *
 * <p>R4. Carries HTTP status, Feishu API code, and a pre-computed
 * retryable flag so callers never parse error strings.
 */
public final class FeishuApiException extends IOException {

    private final int httpStatus;
    private final int apiCode;
    private final boolean retryable;
    private final String sanitizedMessage;

    public FeishuApiException(int httpStatus, int apiCode, String rawMessage) {
        super("feishu http=" + httpStatus + " code=" + apiCode);
        this.httpStatus = httpStatus;
        this.apiCode = apiCode;
        this.retryable = classify(httpStatus, apiCode);
        this.sanitizedMessage = sanitize(rawMessage);
    }

    public FeishuApiException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = 0;
        this.apiCode = -1;
        this.retryable = message != null && (message.contains("timeout") || message.contains("connect"));
        this.sanitizedMessage = message != null ? message.replaceAll("(app_id|app_secret|token)=[^&\\s,]+", "$1=***") : "unknown";
    }

    public int httpStatus() { return httpStatus; }
    public int apiCode() { return apiCode; }
    public boolean retryable() { return retryable; }
    public String sanitizedMessage() { return sanitizedMessage; }

    private static boolean classify(int httpStatus, int apiCode) {
        if (httpStatus == 429) return true;
        if (httpStatus >= 500) return true;
        if (apiCode == 99991600 || apiCode == 99991601) return true; // rate limit
        if (httpStatus == 400 || httpStatus == 401 || httpStatus == 403 || httpStatus == 404)
            return false;
        return false;
    }

    private static String sanitize(String msg) {
        if (msg == null) return "unknown";
        return msg.replaceAll("(app_id|app_secret|token)=[^&\\s,]+", "$1=***");
    }
}
