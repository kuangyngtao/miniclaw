package com.clawkit.engine.impl;

import com.clawkit.engine.ProviderGateway;
import com.clawkit.engine.RunScope;
import com.clawkit.observability.ProviderCallCompletedPayload;
import com.clawkit.observability.ProviderCallStartedPayload;
import com.clawkit.observability.RunEventPayload;
import com.clawkit.observability.RunRecorder;
import com.clawkit.provider.LLMException;
import com.clawkit.provider.LLMProvider;
import com.clawkit.provider.ModelRequest;
import com.clawkit.provider.ModelResponse;
import com.clawkit.provider.StreamObserver;
import com.clawkit.provider.TokenUsage;
import com.clawkit.provider.UsageSource;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    private final AtomicLong providerCallSequence = new AtomicLong();

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
        String providerCallId = nextProviderCallId(scope);
        record(startedPayload(providerCallId, scope, request, false, provider.descriptor()),
            scope);
        try {
            ModelResponse response = provider.generate(request.withControl(control));
            long ms = Duration.between(start, Instant.now()).toMillis();
            long actual = response.usage() != null && response.usage().totalTokens() > 0
                ? response.usage().totalTokens() : reserved;
            budget.settle(reserved, actual);
            int retryCount = response.metadata() != null ? response.metadata().retryCount() : 0;
            record(completedPayload(providerCallId, scope, false, response.usage(),
                response.metadata() != null ? response.metadata().model() : null,
                ms, retryCount, false, null, null), scope);
            return response;
        } catch (Exception e) {
            budget.settle(reserved, reserved);
            long ms = Duration.between(start, Instant.now()).toMillis();
            int retryCount = (e instanceof LLMException le) ? le.retryCount() : 0;
            String errorCode = (e instanceof LLMException le && le.providerError() != null)
                ? le.providerError().getClass().getSimpleName() : null;
            record(completedPayload(providerCallId, scope, false, TokenUsage.EMPTY, null,
                ms, retryCount, true, errorCode, e.getMessage()), scope);
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
        String providerCallId = nextProviderCallId(scope);
        record(startedPayload(providerCallId, scope, request, true, provider.descriptor()),
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
                        budget.settle(reserved, resp.usage() != null && resp.usage().totalTokens() > 0
                            ? resp.usage().totalTokens() : reserved);
                        record(completedPayload(providerCallId, scope, true, resp.usage(),
                            resp.metadata() != null ? resp.metadata().model() : null,
                            ms, resp.metadata() != null ? resp.metadata().retryCount() : 0,
                            false, null, null), scope);
                    }
                    observer.onComplete(resp);
                }
                @Override
                public void onError(com.clawkit.provider.ProviderError error) {
                    if (terminalSent.compareAndSet(false, true)) {
                        long ms = Duration.between(start, Instant.now()).toMillis();
                        budget.settle(reserved, reserved);
                        record(completedPayload(providerCallId, scope, true, TokenUsage.EMPTY,
                            null, ms, 0, true, null, error.toString()), scope);
                    }
                    observer.onError(error);
                }
            });
            if (terminalSent.compareAndSet(false, true)) {
                long ms = Duration.between(start, Instant.now()).toMillis();
                budget.settle(reserved, response.usage() != null && response.usage().totalTokens() > 0
                    ? response.usage().totalTokens() : reserved);
                int r = response.metadata() != null ? response.metadata().retryCount() : 0;
                record(completedPayload(providerCallId, scope, true, response.usage(),
                    response.metadata() != null ? response.metadata().model() : null,
                    ms, r, false, null, null), scope);
            }
            return response;
        } catch (Exception e) {
            if (terminalSent.compareAndSet(false, true)) {
                long ms = Duration.between(start, Instant.now()).toMillis();
                budget.settle(reserved, reserved);
                int r = (e instanceof LLMException le) ? le.retryCount() : 0;
                String ec = (e instanceof LLMException le && le.providerError() != null)
                    ? le.providerError().getClass().getSimpleName() : null;
                record(completedPayload(providerCallId, scope, true, TokenUsage.EMPTY,
                    null, ms, r, true, ec, e.getMessage()), scope);
            }
            throw e;
        }
    }

    private String nextProviderCallId(RunScope scope) {
        return scope.runId() + ":provider:" + providerCallSequence.incrementAndGet();
    }

    private static ProviderCallStartedPayload startedPayload(String providerCallId,
                                                              RunScope scope,
                                                              ModelRequest request,
                                                              boolean streaming,
                                                              com.clawkit.provider.ProviderDescriptor descriptor) {
        String reasoningMode = request.parameters() != null
            && request.parameters().reasoningMode() != null
            ? request.parameters().reasoningMode().name() : null;
        return new ProviderCallStartedPayload(providerCallId, scope.phase().name(), streaming,
            null, null,
            descriptor != null && descriptor.dialect() != null
                ? descriptor.dialect().name() : null,
            descriptor != null ? descriptor.requestedModel() : null,
            reasoningMode, PromptFingerprint.compute(request));
    }

    private static ProviderCallCompletedPayload completedPayload(
        String providerCallId, RunScope scope, boolean streaming, TokenUsage usage,
        String actualModel, long durationMs, int retryCount, boolean failed,
        String errorCode, String errorMessage) {
        TokenUsage safeUsage = usage != null ? usage : TokenUsage.EMPTY;
        UsageSource source = safeUsage.source() != null
            ? safeUsage.source() : UsageSource.UNAVAILABLE;
        return new ProviderCallCompletedPayload(
            providerCallId, scope.phase().name(), streaming,
            safeUsage.promptTokens(), safeUsage.completionTokens(),
            source != UsageSource.ACTUAL, durationMs, retryCount, failed,
            errorCode, errorMessage, actualModel,
            safeUsage.promptCacheHitTokens(), safeUsage.promptCacheMissTokens(),
            safeUsage.reasoningTokens(), source.name());
    }

    private void record(RunEventPayload payload, RunScope scope) {
        try {
            recorder.record(payload, scope.runId(), scope.parentRunId(),
                scope.turn(), Instant.now());
        } catch (Exception ignored) {}
    }
}
