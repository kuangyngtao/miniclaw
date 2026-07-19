package com.clawkit.tools.action;

/**
 * 恢复指令：结果模型的第二正交维度（P1-G0 契约）。
 * 由确定性决策表（clawkit-reliability）从 {@link FailureClass} 推导，LLM 不可修改。
 */
public enum RecoveryDirective {

    /** 允许在次数/冷却/预算约束内自动重试（仅限确认无副作用的失败）。 */
    RETRY_ALLOWED,

    /** 重新采证：结果未知，先收集新证据，不重复执行动作。 */
    RECOLLECT,

    /** 进入 Verification：动作可能已生效，需独立验证。 */
    VERIFY,

    /** 需要人工输入或人工接管。 */
    USER_INPUT,

    /** 需要补偿动作（作为新的、关联原 Attempt 的副作用 Attempt）。 */
    COMPENSATE,

    /** 停止：不重试、不补偿（预算耗尽、取消）。 */
    ABORT
}
