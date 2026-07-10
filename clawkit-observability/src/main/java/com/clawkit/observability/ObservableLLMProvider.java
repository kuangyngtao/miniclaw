package com.clawkit.observability;

import com.clawkit.observability.model.ProviderCallMetrics;
import com.clawkit.provider.LLMException;
import com.clawkit.provider.LLMProvider;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolDefinition;

import java.util.List;
import java.util.function.Consumer;

/**
 * LLMProvider 装饰器，记录每次调用的耗时和成功/失败。
 * 不修改 LLMProvider 接口，在 ClawkitApp 装配时包装。
 *
 * <p>注意：retry 次数在 OpenAIProvider 内部不可见，
 * 当前只能记录最终调用的成功/失败和总耗时。
 */
public class ObservableLLMProvider implements LLMProvider {

    private final LLMProvider delegate;
    private final Consumer<ProviderCallMetrics> onCall;

    public ObservableLLMProvider(LLMProvider delegate, Consumer<ProviderCallMetrics> onCall) {
        this.delegate = delegate;
        this.onCall = onCall;
    }

    @Override
    public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
        long start = System.currentTimeMillis();
        boolean failed = false;
        String errorCode = null;
        String errorMessage = null;
        try {
            return delegate.generate(messages, availableTools);
        } catch (LLMException e) {
            failed = true;
            errorCode = "LLM_ERROR";
            errorMessage = e.getMessage();
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - start;
            fire(new ProviderCallMetrics(
                null, 0, "phase2", false,
                0, 0, true, duration, 0,
                failed, errorCode, errorMessage));
        }
    }

    @Override
    public Message generateStream(List<Message> messages, List<ToolDefinition> tools,
                                   Consumer<String> onToken) {
        long start = System.currentTimeMillis();
        boolean failed = false;
        String errorCode = null;
        String errorMessage = null;
        try {
            return delegate.generateStream(messages, tools, onToken);
        } catch (LLMException e) {
            failed = true;
            errorCode = "LLM_ERROR";
            errorMessage = e.getMessage();
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - start;
            fire(new ProviderCallMetrics(
                null, 0, "phase2", true,
                0, 0, true, duration, 0,
                failed, errorCode, errorMessage));
        }
    }

    @Override
    public int getContextWindow() {
        return delegate.getContextWindow();
    }

    @Override
    public String getEncoding() {
        return delegate.getEncoding();
    }

    private void fire(ProviderCallMetrics metrics) {
        if (onCall != null) {
            try { onCall.accept(metrics); } catch (Exception ignored) {}
        }
    }
}
