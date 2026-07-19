package com.clawkit.tools.action;

/**
 * 副作用确定性：结果模型的第一正交维度（P1-G0 契约）。
 *
 * <p>核心规则：结果未知时不得自动重复写；只有确认无副作用或可信服务端
 * 去重的场景允许自动重试。
 */
public enum EffectCertainty {

    /** 动作未被派发（参数错误、审批拒绝、预算不足、派发前取消）。 */
    NOT_DISPATCHED,

    /** 已确认动作未产生任何副作用（服务端执行前明确拒绝、本地执行前失败）。 */
    NO_EFFECT_CONFIRMED,

    /** 已确认副作用发生（本地完成、服务端确认、幂等键去重返回既有结果）。 */
    EFFECT_CONFIRMED,

    /** 明确的部分执行（多步骤动作中途失败且已知边界）。 */
    PARTIAL_EFFECT,

    /** 结果未知（timeout、断网、中断、进程崩溃、服务端已接受但无最终结果）。 */
    EFFECT_UNKNOWN;

    /** 该确定性下是否允许自动重试同一动作。 */
    public boolean allowsAutoRetry() {
        return this == NOT_DISPATCHED || this == NO_EFFECT_CONFIRMED;
    }
}
