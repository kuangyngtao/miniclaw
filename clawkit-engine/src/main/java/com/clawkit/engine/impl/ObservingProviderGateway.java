package com.clawkit.engine.impl;

import com.clawkit.engine.ProviderGateway;
import com.clawkit.engine.RunScope;
import com.clawkit.observability.ProviderCallCompletedPayload;
import com.clawkit.observability.ProviderCallStartedPayload;
import com.clawkit.observability.RunEventPayload;
import com.clawkit.observability.RunRecorder;
import com.clawkit.provider.LLMProvider;
import com.clawkit.provider.ModelRequest;
import com.clawkit.provider.ModelResponse;
import com.clawkit.provider.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ProviderGateway 观测实现：包装 LLMProvider，直接把事件写入 RunRecorder。
 */
public class ObservingProviderGateway implements ProviderGateway {

    private final LLMProvider provider;
    private final RunRecorder recorder;

    public ObservingProviderGateway(LLMProvider provider, RunRecorder recorder) {
        this.provider = provider;
        this.recorder = recorder;
    }

    @Override
    public ModelResponse generate(ModelRequest request, RunScope scope) {
        Instant start = Instant.now();
        record(new ProviderCallStartedPayload(scope.runId(), scope.phase().name(), false),
            scope);
        try {
            ModelResponse response = provider.generate(request);
            long ms = Duration.between(start, Instant.now()).toMillis();
            int in = response.usage() != null ? response.usage().promptTokens() : 0;
            int out = response.usage() != null ? response.usage().completionTokens() : 0;
            record(new ProviderCallCompletedPayload(scope.runId(), scope.phase().name(),
                false, in, out, true, ms, 0, false, null, null), scope);
            return response;
        } catch (Exception e) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            record(new ProviderCallCompletedPayload(scope.runId(), scope.phase().name(),
                false, 0, 0, false, ms, 0, true, null, e.getMessage()), scope);
            throw e;
        }
    }

    @Override
    public ModelResponse generateStream(ModelRequest request, RunScope scope, StreamObserver observer) {
        Instant start = Instant.now();
        record(new ProviderCallStartedPayload(scope.runId(), scope.phase().name(), true),
            scope);
        var terminalSent = new AtomicBoolean(false);
        try {
            ModelResponse response = provider.generateStream(request, new StreamObserver() {
                @Override public void onContent(String d) { observer.onContent(d); }
                @Override public void onToolCallDelta(int i, String id, String n, String d) {
                    observer.onToolCallDelta(i, id, n, d);
                }
                @Override
                public void onComplete(ModelResponse resp) {
                    if (terminalSent.compareAndSet(false, true)) {
                        long ms = Duration.between(start, Instant.now()).toMillis();
                        int in = resp.usage() != null ? resp.usage().promptTokens() : 0;
                        int out = resp.usage() != null ? resp.usage().completionTokens() : 0;
                        record(new ProviderCallCompletedPayload(scope.runId(),
                            scope.phase().name(), true, in, out, true, ms, 0,
                            false, null, null), scope);
                    }
                    observer.onComplete(resp);
                }
                @Override
                public void onError(com.clawkit.provider.ProviderError error) {
                    if (terminalSent.compareAndSet(false, true)) {
                        long ms = Duration.between(start, Instant.now()).toMillis();
                        record(new ProviderCallCompletedPayload(scope.runId(),
                            scope.phase().name(), true, 0, 0, false, ms, 0,
                            true, null, error.toString()), scope);
                    }
                    observer.onError(error);
                }
            });
            if (terminalSent.compareAndSet(false, true)) {
                long ms = Duration.between(start, Instant.now()).toMillis();
                int in = response.usage() != null ? response.usage().promptTokens() : 0;
                int out = response.usage() != null ? response.usage().completionTokens() : 0;
                record(new ProviderCallCompletedPayload(scope.runId(),
                    scope.phase().name(), true, in, out, true, ms, 0,
                    false, null, null), scope);
            }
            return response;
        } catch (Exception e) {
            if (terminalSent.compareAndSet(false, true)) {
                long ms = Duration.between(start, Instant.now()).toMillis();
                record(new ProviderCallCompletedPayload(scope.runId(),
                    scope.phase().name(), true, 0, 0, false, ms, 0,
                    true, null, e.getMessage()), scope);
            }
            throw e;
        }
    }

    private void record(RunEventPayload payload, RunScope scope) {
        try {
            recorder.record(payload, scope.runId(), scope.parentRunId(),
                scope.turn(), Instant.now());
        } catch (Exception ignored) {}
    }
}
