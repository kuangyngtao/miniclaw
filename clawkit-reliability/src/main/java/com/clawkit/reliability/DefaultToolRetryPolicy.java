package com.clawkit.reliability;

import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.action.RecoveryDirective;

import java.time.Duration;
import java.util.Random;

/**
 * 默认工具重试策略（P1-A2）：full jitter + 八重门禁。
 *
 * <p>只有以下条件<b>全部</b>满足才允许同输入重试：
 * <ol>
 *   <li>metadata.readOnly == true</li>
 *   <li>metadata.sideEffects 为空</li>
 *   <li>metadata.provenance.trusted == true</li>
 *   <li>lastResult.effectCertainty == NO_EFFECT_CONFIRMED</li>
 *   <li>FailureDecisionTable.directiveFor(...) == RETRY_ALLOWED</li>
 *   <li>lastResult.toolError != null && toolError.retryable == true</li>
 *   <li>attemptsMade < maxAttempts</li>
 *   <li>取消/deadline/预算均允许</li>
 * </ol>
 *
 * <p>退避使用 full jitter：
 * <pre>cap = min(200ms × 2^(attemptsMade-1), 2000ms)
 * delay = random(0, cap)</pre>
 */
public final class DefaultToolRetryPolicy implements ToolRetryPolicy {

    private static final long BASE_DELAY_MS = 200;
    private static final long MAX_CAP_MS = 2000;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final Random random;
    private final int maxAttempts;

    public DefaultToolRetryPolicy(Random random) {
        this(random, DEFAULT_MAX_ATTEMPTS);
    }

    public DefaultToolRetryPolicy(Random random, int maxAttempts) {
        this.random = random;
        this.maxAttempts = maxAttempts;
    }

    public DefaultToolRetryPolicy() {
        this(new Random(), DEFAULT_MAX_ATTEMPTS);
    }

    @Override
    public ToolRetryDecision decide(ToolRetryContext ctx) {
        var result = ctx.lastResult();
        var meta = ctx.metadata();

        // Gate 1: 只读
        if (!meta.isReadOnly()) {
            return ToolRetryDecision.stop(ToolRetryDecision.NOT_READ_ONLY);
        }

        // Gate 2: 无副作用声明
        var fx = meta.sideEffects();
        if (fx != null && !fx.isEmpty()) {
            return ToolRetryDecision.stop(ToolRetryDecision.ACTION_NOT_RETRY);
        }

        // Gate 3: 可信元数据
        var provenance = meta.provenance();
        if (provenance == null || !provenance.trusted()) {
            return ToolRetryDecision.stop(ToolRetryDecision.UNTRUSTED_METADATA);
        }

        // Gate 4: 确认无副作用
        if (result.effectCertainty() != EffectCertainty.NO_EFFECT_CONFIRMED) {
            return ToolRetryDecision.stop(ToolRetryDecision.ACTION_NOT_RETRY);
        }

        // Gate 5: 恢复指令允许
        if (result.failureClass() == null
            || FailureDecisionTable.directiveFor(result.failureClass()) != RecoveryDirective.RETRY_ALLOWED) {
            return ToolRetryDecision.stop(ToolRetryDecision.NOT_RETRYABLE);
        }

        // Gate 6: ToolError 声明为可重试
        var toolError = result.toolError();
        if (toolError == null || !toolError.retryable()) {
            return ToolRetryDecision.stop(ToolRetryDecision.NOT_RETRYABLE);
        }

        // Gate 7: 未达上限
        if (ctx.attemptsMade() >= ctx.maxAttempts()) {
            return ToolRetryDecision.stop(ToolRetryDecision.ATTEMPT_LIMIT);
        }

        // Gate 8: deadline/remaining 检查（由调用方在 wait 前后执行 control checkpoint）
        if (ctx.remaining() != null && ctx.remaining().isNegative()) {
            return ToolRetryDecision.stop(ToolRetryDecision.DEADLINE_TOO_CLOSE);
        }

        // Full jitter backoff
        long cap = Math.min(BASE_DELAY_MS * (1L << (ctx.attemptsMade() - 1)), MAX_CAP_MS);
        long delayMs = cap > 0 ? random.nextLong(cap + 1) : 0;

        return ToolRetryDecision.retry(
            Duration.ofMillis(delayMs), ToolRetryDecision.TRANSIENT_READ_FAILURE);
    }

    /** 公开以便测试注入 */
    public int maxAttempts() {
        return maxAttempts;
    }
}
