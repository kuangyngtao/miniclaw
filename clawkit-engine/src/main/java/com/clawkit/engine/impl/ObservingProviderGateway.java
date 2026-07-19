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
 *
 * <p>P1-G1：模型请求唯一入口同时是预算与取消的硬拦截点——
 * 调用前 checkpoint（取消/deadline/预算耗尽直接拒绝，不发起网络请求），
 * 调用前按估算预留 token，响应后按真实 usage 结算。
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
        var control = scope.control();
        control.acquireProviderCall(); // 取消/deadline/调用次数预算硬门禁

        var budget = control.tokenBudget();
        long estimate = estimateTokens(request);
        long reserved = budget.reserveUpTo(estimate);
        if (budget.limited() && reserved < estimate) {
            budget.settle(reserved, 0);
            throw new com.clawkit.tools.control.ExecutionHaltedException(
                com.clawkit.tools.control.ExecutionHaltedException.Reason.BUDGET_EXHAUSTED,
                "insufficient token budget: need " + estimate + ", remaining " + reserved);
        }

        Instant start = Instant.now();
        record(new ProviderCallStartedPayload(scope.runId(), scope.phase().name(), false),
            scope);
        try {
            ModelResponse response = provider.generate(request.withControl(control));
            long ms = Duration.between(start, Instant.now()).toMillis();
            int in = response.usage() != null ? response.usage().promptTokens() : 0;
            int out = response.usage() != null ? response.usage().completionTokens() : 0;
            long actual = response.usage() != null && response.usage().totalTokens() > 0
                ? response.usage().totalTokens() : reserved;
            budget.settle(reserved, actual);
            record(new ProviderCallCompletedPayload(scope.runId(), scope.phase().name(),
                false, in, out, true, ms, 0, false, null, null), scope);
            return response;
        } catch (Exception e) {
            // 失败调用无法确认服务端是否计费，保守不退还预留
            budget.settle(reserved, reserved);
            long ms = Duration.between(start, Instant.now()).toMillis();
            record(new ProviderCallCompletedPayload(scope.runId(), scope.phase().name(),
                false, 0, 0, false, ms, 0, true, null, e.getMessage()), scope);
            throw e;
        }
    }

    /** 估算请求 token：字符数/4 + 输出余量。仅用于预留，结算以真实 usage 为准。 */
    static long estimateTokens(ModelRequest request) {
        long chars = 0;
        for (var msg : request.messages()) {
            if (msg.content() != null) chars += msg.content().length();
        }
        return chars / 4 + 1024;
    }

    @Override
    public ModelResponse generateStream(ModelRequest request, RunScope scope, StreamObserver observer) {
        var control = scope.control();
        control.acquireProviderCall();

        var budget = control.tokenBudget();
        long estimate = estimateTokens(request);
        long reserved = budget.reserveUpTo(estimate);
        if (budget.limited() && reserved < estimate) {
            budget.settle(reserved, 0);
            throw new com.clawkit.tools.control.ExecutionHaltedException(
                com.clawkit.tools.control.ExecutionHaltedException.Reason.BUDGET_EXHAUSTED,
                "insufficient token budget: need " + estimate + ", remaining " + reserved);
        }

        Instant start = Instant.now();
        record(new ProviderCallStartedPayload(scope.runId(), scope.phase().name(), true),
            scope);
        var terminalSent = new AtomicBoolean(false);
        try {
            ModelResponse response = provider.generateStream(request.withControl(control), new StreamObserver() {
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
                        budget.settle(reserved, resp.usage() != null && resp.usage().totalTokens() > 0
                            ? resp.usage().totalTokens() : reserved);
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
                        budget.settle(reserved, reserved);
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
                budget.settle(reserved, response.usage() != null && response.usage().totalTokens() > 0
                    ? response.usage().totalTokens() : reserved);
                record(new ProviderCallCompletedPayload(scope.runId(),
                    scope.phase().name(), true, in, out, true, ms, 0,
                    false, null, null), scope);
            }
            return response;
        } catch (Exception e) {
            if (terminalSent.compareAndSet(false, true)) {
                long ms = Duration.between(start, Instant.now()).toMillis();
                budget.settle(reserved, reserved);
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
