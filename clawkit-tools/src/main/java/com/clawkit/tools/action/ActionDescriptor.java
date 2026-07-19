package com.clawkit.tools.action;

import com.clawkit.tools.ToolRiskLevel;
import java.util.List;
import java.util.Objects;

/**
 * 动作描述符：副作用工具在执行前必须能够根据参数生成的可信描述（P1-G0 契约）。
 *
 * <p>{@code readOnly=false} 或声明了 side effect 的工具若无法生成 ActionDescriptor，
 * 统一执行器 fail closed 拒绝执行。字段必须可 JSON 序列化（进入 Reliability Journal）。
 *
 * <p>审批授权绑定 {@link #fingerprint()}：actionCode、canonical target、参数摘要、
 * 风险/可逆性、expected effects、验证与补偿策略、blast radius 任一漂移都会改变指纹，
 * 必须重新门禁。
 */
public record ActionDescriptor(
    String actionCode,
    String canonicalTarget,
    String parameterDigest,
    ToolRiskLevel riskLevel,
    Reversibility reversibility,
    ActionReliability reliability,
    VerificationMode verificationMode,
    List<String> preconditions,
    List<String> expectedEffects,
    String compensationSummary,
    String blastRadius
) {
    public ActionDescriptor {
        requireNonBlank(actionCode, "actionCode");
        requireNonBlank(canonicalTarget, "canonicalTarget");
        requireNonBlank(parameterDigest, "parameterDigest");
        Objects.requireNonNull(riskLevel, "riskLevel required");
        Objects.requireNonNull(reversibility, "reversibility required");
        Objects.requireNonNull(reliability, "reliability required");
        Objects.requireNonNull(verificationMode, "verificationMode required");
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        expectedEffects = expectedEffects == null ? List.of() : List.copyOf(expectedEffects);
        if (compensationSummary == null) compensationSummary = "";
        if (blastRadius == null) blastRadius = "";
    }

    /** 目标互斥键：同一资源的不同工具/参数共享同一 targetKey。 */
    public String targetKey() {
        return canonicalTarget;
    }

    /**
     * 内容派生的逻辑动作 ID：同一动作被模型用不同 toolCallId 重发时保持稳定。
     * Ops 场景应改用计划步骤提供的 logicalActionId。
     */
    public String contentDerivedActionId() {
        return actionCode + ":" + Digests.sha256Hex(canonicalTarget + "\0" + parameterDigest)
            .substring(0, 16);
    }

    /** 审批与幂等绑定的不可变指纹。 */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        sb.append(actionCode).append('\0')
          .append(canonicalTarget).append('\0')
          .append(parameterDigest).append('\0')
          .append(riskLevel.name()).append('\0')
          .append(reversibility.name()).append('\0')
          .append(verificationMode.name()).append('\0')
          .append(compensationSummary).append('\0')
          .append(blastRadius).append('\0');
        for (String p : preconditions) sb.append("p:").append(p).append('\0');
        for (String e : expectedEffects) sb.append("e:").append(e).append('\0');
        return Digests.sha256Hex(sb.toString());
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " required");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
