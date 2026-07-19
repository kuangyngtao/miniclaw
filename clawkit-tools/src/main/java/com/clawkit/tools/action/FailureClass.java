package com.clawkit.tools.action;

/**
 * 确定性失败分类（P1-G0 契约）。
 *
 * <p>每个分类携带固定的 {@link EffectCertainty}；LLM 只能解释结果，
 * 不能修改分类到确定性的映射。恢复指令映射见 clawkit-reliability 的决策表。
 */
public enum FailureClass {

    /** 非失败（成功结果）。 */
    NONE(EffectCertainty.EFFECT_CONFIRMED),

    /** 参数无效，未派发。 */
    INVALID_ARGUMENTS(EffectCertainty.NOT_DISPATCHED),

    /** 前置条件不满足（precheck 失败、目标漂移、工具缺失），未派发。 */
    PRECONDITION_FAILED(EffectCertainty.NOT_DISPATCHED),

    /** 审批被拒绝，未派发。 */
    APPROVAL_REJECTED(EffectCertainty.NOT_DISPATCHED),

    /** 权限/策略阻断（PLAN 模式、Safety、Side Effect Gate），未派发。 */
    PERMISSION_BLOCKED(EffectCertainty.NOT_DISPATCHED),

    /** 预算耗尽，未派发。 */
    BUDGET_EXHAUSTED(EffectCertainty.NOT_DISPATCHED),

    /** 派发前取消，未派发。 */
    CANCELLED_BEFORE_DISPATCH(EffectCertainty.NOT_DISPATCHED),

    /** 派发前 deadline 超限，未派发。 */
    DEADLINE_EXCEEDED_BEFORE_DISPATCH(EffectCertainty.NOT_DISPATCHED),

    /** 服务端在执行前明确拒绝，确认无副作用。 */
    SERVER_REJECTED_BEFORE_EXECUTION(EffectCertainty.NO_EFFECT_CONFIRMED),

    /** 本地在产生任何副作用前确定性失败（如写前校验失败）。 */
    LOCAL_ERROR_NO_EFFECT(EffectCertainty.NO_EFFECT_CONFIRMED),

    /** 超时，结果未知：远端/进程可能仍在执行、已完成或未开始。 */
    TIMEOUT_OUTCOME_UNKNOWN(EffectCertainty.EFFECT_UNKNOWN),

    /** 执行中被中断/取消，结果未知。 */
    INTERRUPTED_OUTCOME_UNKNOWN(EffectCertainty.EFFECT_UNKNOWN),

    /** 连接丢失/网络中断，结果未知。 */
    CONNECTION_LOST(EffectCertainty.EFFECT_UNKNOWN),

    /** 服务端已接受但无最终结果：只允许查询状态，不得重复提交。 */
    ACCEPTED_NO_FINAL_RESULT(EffectCertainty.EFFECT_UNKNOWN),

    /** 服务端以同一幂等键返回既有结果：副作用已确认，进入 Verification。 */
    SERVER_DEDUP_REPLAY(EffectCertainty.EFFECT_CONFIRMED),

    /** 明确的部分执行。 */
    PARTIAL_EXECUTION(EffectCertainty.PARTIAL_EFFECT),

    /** 执行过程中出错，副作用状态未知。 */
    EXECUTION_ERROR_OUTCOME_UNKNOWN(EffectCertainty.EFFECT_UNKNOWN),

    /** 无法分类，保守按结果未知处理。 */
    UNCLASSIFIED(EffectCertainty.EFFECT_UNKNOWN);

    private final EffectCertainty certainty;

    FailureClass(EffectCertainty certainty) {
        this.certainty = certainty;
    }

    /** 该失败分类固有的副作用确定性（确定性映射的唯一事实来源）。 */
    public EffectCertainty certainty() {
        return certainty;
    }
}
