package com.clawkit.engine.impl;

import com.clawkit.provider.LLMException;
import com.clawkit.provider.ProviderError;

/** Maps structured provider failures to safe, actionable user text. */
final class ProviderFailureMessage {
    private ProviderFailureMessage() {}

    static String format(LLMException exception) {
        return format(exception, null);
    }

    static String format(LLMException exception, String phase) {
        ProviderError error = exception.providerError();
        String reason;
        String next;
        if (error == null) {
            String detail = sanitize(exception.getMessage());
            reason = (phase == null || phase.isBlank() ? "" : phase + " ")
                + "LLM 调用失败: " + detail;
            next = "Retry; inspect /trace if the failure persists.";
        } else if (error instanceof ProviderError.Authentication) {
            reason = "DeepSeek authentication failed";
            next = "Set a valid CLAWKIT_API_KEY in the environment and retry.";
        } else if (error instanceof ProviderError.RateLimited) {
            reason = "DeepSeek rate limit exceeded";
            next = "Wait briefly, reduce request frequency, and retry.";
        } else if (error instanceof ProviderError.Timeout) {
            reason = "DeepSeek request timed out";
            next = "Check the network or increase provider.requestTimeoutSeconds.";
        } else if (error instanceof ProviderError.ContextLengthExceeded) {
            reason = "The request exceeded the model context limit";
            next = "Run /compact or start a new session, then retry.";
        } else if (error instanceof ProviderError.Network) {
            reason = "Cannot reach DeepSeek";
            next = "Check DNS, proxy, firewall, and network connectivity.";
        } else if (error instanceof ProviderError.Cancelled) {
            reason = "The DeepSeek request was cancelled";
            next = "Retry when ready.";
        } else if (error instanceof ProviderError.Protocol) {
            reason = "DeepSeek rejected the request";
            next = "Check the selected model and request parameters.";
        } else {
            reason = "DeepSeek service request failed";
            next = "Retry later; inspect /trace if the failure persists.";
        }
        return "[A-002] " + reason
            + "\nImpact: the current task stopped before a model response"
            + "\nNext: " + next;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "unknown error";
        String safe = value.replaceAll("(?i)(bearer\\s+|sk-)[A-Za-z0-9._-]+", "$1***");
        return safe.length() > 200 ? safe.substring(0, 200) + "..." : safe;
    }
}
