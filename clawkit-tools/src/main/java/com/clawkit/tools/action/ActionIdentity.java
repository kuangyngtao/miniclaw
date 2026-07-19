package com.clawkit.tools.action;

import java.util.Objects;

/**
 * 动作身份：贯穿 Attempt 生命周期的稳定标识（P1-G0 契约）。
 *
 * <p>logicalActionId 由计划步骤或参数内容派生，绝不使用模型生成的
 * toolCallId——防止模型用新 toolCallId 重发绕过去重。
 */
public record ActionIdentity(
    String logicalActionId,
    String idempotencyKey,
    String targetKey,
    String actionFingerprint
) {
    public ActionIdentity {
        requireNonBlank(logicalActionId, "logicalActionId");
        requireNonBlank(idempotencyKey, "idempotencyKey");
        requireNonBlank(targetKey, "targetKey");
        requireNonBlank(actionFingerprint, "actionFingerprint");
    }

    /** idempotencyKey = logicalActionId + "#" + attemptSeq。 */
    public static ActionIdentity of(String logicalActionId, int attemptSeq,
                                    String targetKey, String actionFingerprint) {
        if (attemptSeq < 1) throw new IllegalArgumentException("attemptSeq must be >= 1");
        return new ActionIdentity(logicalActionId,
            logicalActionId + "#" + attemptSeq, targetKey, actionFingerprint);
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " required");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
