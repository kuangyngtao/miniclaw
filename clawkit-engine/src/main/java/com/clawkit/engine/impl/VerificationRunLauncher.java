package com.clawkit.engine.impl;

import com.clawkit.engine.AgentRuntimeDependencies;
import com.clawkit.engine.PermissionMode;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.reliability.attempt.ActionAttempt;
import com.clawkit.reliability.gate.DeterministicVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 独立 Verification Run（P1-G5）。
 *
 * <p>隔离要求：
 * <ul>
 *   <li>新 root runId（不设置 parentRunId，不继承修复会话）；</li>
 *   <li>全新 AgentEngine 实例（空 session，无修复上下文/结论/工具输出）；</li>
 *   <li>只读权限（PLAN）：verifier 不允许产生新副作用；</li>
 *   <li>先跑确定性断言（现场重新采集证据），再允许模型解释；</li>
 *   <li>模型结论不具备判定权威——机械判定只来自确定性断言。</li>
 * </ul>
 * 验证输入只包括不可变 Action Contract、expected effects 与验证策略。
 */
public final class VerificationRunLauncher {

    private static final Logger log = LoggerFactory.getLogger(VerificationRunLauncher.class);

    public record VerificationRunResult(
        String relatedAttemptId,
        boolean deterministicPassed,
        String deterministicDetail,
        String modelConclusion
    ) {}

    private final AgentRuntimeDependencies deps;
    private final String workDir;

    public VerificationRunLauncher(AgentRuntimeDependencies deps, String workDir) {
        this.deps = deps;
        this.workDir = workDir;
    }

    public VerificationRunResult verify(ActionAttempt attempt) {
        // 1. 确定性断言先行：新采集证据，observedAt 必然晚于动作结束
        DeterministicVerifier.Verdict verdict =
            DeterministicVerifier.verify(attempt.descriptor().expectedEffects());

        // 2. 独立模型复查：新 root run、全新引擎、只读权限
        AgentEngine verifier = new AgentEngine(deps, workDir, ThinkingMode.OFF, "");
        verifier.setPermissionMode(PermissionMode.PLAN);
        verifier.enableSubAgents = false;
        String conclusion;
        try {
            conclusion = verifier.run(buildPrompt(attempt, verdict));
        } catch (Exception e) {
            log.warn("[Verification] model review failed for {}: {}",
                attempt.attemptId(), e.getMessage());
            conclusion = "[verification model review failed: " + e.getMessage() + "]";
        }
        return new VerificationRunResult(attempt.attemptId(),
            verdict.passed(), verdict.detail(), conclusion);
    }

    /** 验证输入只包含不可变 Action Contract——绝不注入修复会话内容。 */
    private static String buildPrompt(ActionAttempt attempt, DeterministicVerifier.Verdict verdict) {
        var d = attempt.descriptor();
        StringBuilder sb = new StringBuilder();
        sb.append("[VERIFICATION] 独立验证任务（attempt=").append(attempt.attemptId()).append("）\n\n")
          .append("你是独立验证者，与执行该动作的会话完全隔离。不要信任任何先前结论，")
          .append("只允许使用只读工具重新采集证据。\n\n")
          .append("## Action Contract\n")
          .append("- actionCode: ").append(d.actionCode()).append('\n')
          .append("- target: ").append(d.canonicalTarget()).append('\n')
          .append("- risk/reversibility: ").append(d.riskLevel()).append('/')
          .append(d.reversibility()).append('\n')
          .append("- verification policy: ").append(d.verificationMode()).append('\n');
        if (!d.expectedEffects().isEmpty()) {
            sb.append("- expected effects:\n");
            d.expectedEffects().forEach(e -> sb.append("  - ").append(e).append('\n'));
        }
        sb.append("\n## 确定性断言结果（先行，具备判定权威）\n")
          .append(verdict.passed() ? "PASSED" : "FAILED").append(": ")
          .append(verdict.detail()).append('\n')
          .append("\n请基于只读证据复查：动作声明的效果是否真实达成、是否存在表面恢复")
          .append("或数据不一致。输出你的独立结论；你的结论不能推翻确定性断言。");
        return sb.toString();
    }
}
