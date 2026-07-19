package com.clawkit.reliability.attempt;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Attempt 状态机（P1-G3）。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>DISPATCH_INTENT 之后的取消/超时/崩溃一律进入 OUTCOME_UNKNOWN，不得当作无副作用失败；</li>
 *   <li>OUTCOME_UNKNOWN 不释放目标锁，禁止自动重复写；</li>
 *   <li>终态与人工接管（ESCALATED）不可被迟到事件反转。</li>
 * </ul>
 */
public enum AttemptState {

    CREATED,
    WAITING_APPROVAL,
    PRECHECKING,
    READY,
    /** durable intent：落盘后即使未真正发送，恢复时也按可能已发送处理 */
    DISPATCH_INTENT,
    EXECUTION_REPORTED,
    VERIFICATION_PENDING,
    VERIFYING,
    /** 终态：独立验证通过 */
    VERIFIED_SUCCESS,
    /** 终态：派发前取消，确认无副作用 */
    CANCELLED_NO_EFFECT,
    /** 终态：确认无副作用的失败（允许按次数/冷却重试新 Attempt） */
    FAILED_NO_EFFECT,
    /** 结果未知：sticky，目标锁不自动过期 */
    OUTCOME_UNKNOWN,
    RECONCILING,
    /** 等待补偿（补偿是独立的新 Attempt） */
    COMPENSATION_PENDING,
    /** 终态：补偿完成 */
    COMPENSATED,
    /** 终态：升级人工接管 */
    ESCALATED;

    private static final Map<AttemptState, Set<AttemptState>> LEGAL = new EnumMap<>(AttemptState.class);

    static {
        LEGAL.put(CREATED, Set.of(WAITING_APPROVAL, PRECHECKING, CANCELLED_NO_EFFECT, FAILED_NO_EFFECT));
        LEGAL.put(WAITING_APPROVAL, Set.of(PRECHECKING, CANCELLED_NO_EFFECT, FAILED_NO_EFFECT));
        LEGAL.put(PRECHECKING, Set.of(READY, CANCELLED_NO_EFFECT, FAILED_NO_EFFECT));
        LEGAL.put(READY, Set.of(DISPATCH_INTENT, CANCELLED_NO_EFFECT, FAILED_NO_EFFECT));
        LEGAL.put(DISPATCH_INTENT, Set.of(EXECUTION_REPORTED, FAILED_NO_EFFECT, OUTCOME_UNKNOWN));
        LEGAL.put(EXECUTION_REPORTED, Set.of(VERIFICATION_PENDING, OUTCOME_UNKNOWN, ESCALATED));
        LEGAL.put(VERIFICATION_PENDING, Set.of(VERIFYING, ESCALATED));
        LEGAL.put(VERIFYING, Set.of(VERIFIED_SUCCESS, VERIFICATION_PENDING, COMPENSATION_PENDING, ESCALATED));
        LEGAL.put(OUTCOME_UNKNOWN, Set.of(RECONCILING, ESCALATED));
        LEGAL.put(RECONCILING, Set.of(VERIFICATION_PENDING, FAILED_NO_EFFECT, OUTCOME_UNKNOWN, ESCALATED));
        LEGAL.put(COMPENSATION_PENDING, Set.of(COMPENSATED, ESCALATED));
        LEGAL.put(VERIFIED_SUCCESS, Set.of());
        LEGAL.put(CANCELLED_NO_EFFECT, Set.of());
        LEGAL.put(FAILED_NO_EFFECT, Set.of());
        LEGAL.put(COMPENSATED, Set.of());
        LEGAL.put(ESCALATED, Set.of());
    }

    public boolean isTerminal() {
        return LEGAL.get(this).isEmpty();
    }

    /**
     * 该状态是否释放 targetKey 互斥。
     *
     * <p>持锁范围 = 派发不确定窗口 + 效果错误窗口：
     * 锁一直保持到验证完成或人工接管终结。否则同目标的新写入可能污染
     * VERIFICATION_PENDING/VERIFYING 正在采集的证据。
     */
    public boolean releasesTarget() {
        return this == VERIFIED_SUCCESS
            || this == CANCELLED_NO_EFFECT
            || this == FAILED_NO_EFFECT
            || this == COMPENSATED
            || this == ESCALATED; // 人工接管后由人决定，不再阻塞自动化通道
    }

    public boolean canTransitionTo(AttemptState to) {
        return LEGAL.get(this).contains(to);
    }
}
