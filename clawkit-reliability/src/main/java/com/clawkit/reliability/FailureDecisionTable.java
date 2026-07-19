package com.clawkit.reliability;

import com.clawkit.tools.action.ActionReliability;
import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.action.FailureClass;
import com.clawkit.tools.action.RecoveryDirective;

import java.util.EnumMap;
import java.util.Map;

/**
 * 确定性失败决策表（P1-G）。
 *
 * <p>FailureClass → RecoveryDirective 的映射是固定代码，LLM 只能解释结果，
 * 不能修改决策。自动重试仅允许：
 * <ul>
 *   <li>确认原动作未产生副作用（NOT_DISPATCHED / NO_EFFECT_CONFIRMED）；</li>
 *   <li>可信服务端以同一 idempotencyKey 去重；</li>
 *   <li>本地可信代码证明为设置型幂等操作且可重新采证。</li>
 * </ul>
 */
public final class FailureDecisionTable {

    private static final Map<FailureClass, RecoveryDirective> TABLE = new EnumMap<>(FailureClass.class);

    static {
        TABLE.put(FailureClass.NONE, RecoveryDirective.VERIFY);
        TABLE.put(FailureClass.INVALID_ARGUMENTS, RecoveryDirective.RETRY_ALLOWED);
        TABLE.put(FailureClass.PRECONDITION_FAILED, RecoveryDirective.RECOLLECT);
        TABLE.put(FailureClass.APPROVAL_REJECTED, RecoveryDirective.USER_INPUT);
        TABLE.put(FailureClass.PERMISSION_BLOCKED, RecoveryDirective.USER_INPUT);
        TABLE.put(FailureClass.BUDGET_EXHAUSTED, RecoveryDirective.ABORT);
        TABLE.put(FailureClass.CANCELLED_BEFORE_DISPATCH, RecoveryDirective.ABORT);
        TABLE.put(FailureClass.SERVER_REJECTED_BEFORE_EXECUTION, RecoveryDirective.RETRY_ALLOWED);
        TABLE.put(FailureClass.LOCAL_ERROR_NO_EFFECT, RecoveryDirective.RETRY_ALLOWED);
        TABLE.put(FailureClass.TIMEOUT_OUTCOME_UNKNOWN, RecoveryDirective.RECOLLECT);
        TABLE.put(FailureClass.INTERRUPTED_OUTCOME_UNKNOWN, RecoveryDirective.RECOLLECT);
        TABLE.put(FailureClass.CONNECTION_LOST, RecoveryDirective.RECOLLECT);
        TABLE.put(FailureClass.ACCEPTED_NO_FINAL_RESULT, RecoveryDirective.VERIFY);
        TABLE.put(FailureClass.SERVER_DEDUP_REPLAY, RecoveryDirective.VERIFY);
        TABLE.put(FailureClass.PARTIAL_EXECUTION, RecoveryDirective.VERIFY);
        TABLE.put(FailureClass.EXECUTION_ERROR_OUTCOME_UNKNOWN, RecoveryDirective.RECOLLECT);
        TABLE.put(FailureClass.UNCLASSIFIED, RecoveryDirective.USER_INPUT);
    }

    private FailureDecisionTable() {}

    /** 每个失败分类的确定性恢复指令。 */
    public static RecoveryDirective directiveFor(FailureClass failureClass) {
        RecoveryDirective directive = TABLE.get(failureClass);
        if (directive == null) {
            // 未登记的新分类保守升级人工
            return RecoveryDirective.USER_INPUT;
        }
        return directive;
    }

    /**
     * 是否允许对该失败自动重试同一动作。
     * 结果未知时仅当动作声明了可信去重/幂等能力才允许。
     */
    public static boolean autoRetryAllowed(FailureClass failureClass, ActionReliability reliability) {
        if (directiveFor(failureClass) == RecoveryDirective.RETRY_ALLOWED) {
            return true;
        }
        EffectCertainty certainty = failureClass.certainty();
        if (certainty == EffectCertainty.EFFECT_UNKNOWN && reliability != null) {
            return reliability.serverDedupSupported()
                || (reliability.locallyProvenIdempotent() && reliability.reconcileSupported());
        }
        return false;
    }
}
