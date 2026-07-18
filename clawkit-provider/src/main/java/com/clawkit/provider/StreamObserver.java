package com.clawkit.provider;

/** 流式响应观察者。 */
public interface StreamObserver {
    void onContent(String delta);
    void onToolCallDelta(int index, String toolCallId, String toolName, String argumentsDelta);
    void onComplete(ModelResponse response);
    void onError(ProviderError error);
}
