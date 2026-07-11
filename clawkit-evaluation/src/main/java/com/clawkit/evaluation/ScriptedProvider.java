package com.clawkit.evaluation;

import com.clawkit.provider.LLMException;
import com.clawkit.provider.LLMProvider;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 严格校验的 fake LLMProvider。
 * 按 ScriptedStep 顺序返回响应，每步校验 phase / turn / availableTools。
 *
 * <p>校验规则：
 * <ul>
 *   <li>脚本耗尽 → 抛出 SCRIPT_EXHAUSTED（不返回默认成功文本）</li>
 *   <li>执行结束仍有未消费步骤 → ScriptConsumptionScorer 报 FAIL</li>
 *   <li>phase 不匹配 → 抛出 SCRIPT_PHASE_MISMATCH</li>
 *   <li>turn 不匹配 → 抛出 SCRIPT_TURN_MISMATCH</li>
 *   <li>availableTools 不匹配 → 抛出 SCRIPT_TOOLS_MISMATCH</li>
 * </ul>
 *
 * <p>每 case/trial 创建全新实例，禁止跨 run 共享。
 */
public class ScriptedProvider implements LLMProvider {

    public static final String SCRIPT_EXHAUSTED = "SCRIPT_EXHAUSTED";
    public static final String SCRIPT_PHASE_MISMATCH = "SCRIPT_PHASE_MISMATCH";
    public static final String SCRIPT_TURN_MISMATCH = "SCRIPT_TURN_MISMATCH";
    public static final String SCRIPT_TOOLS_MISMATCH = "SCRIPT_TOOLS_MISMATCH";

    private final List<ScriptedStep> steps;
    private int index = 0;
    private int callCount = 0;

    public ScriptedProvider(List<ScriptedStep> steps) {
        this.steps = List.copyOf(steps);
    }

    @Override
    public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
        return doGenerate(messages, availableTools, false);
    }

    @Override
    public Message generateStream(List<Message> messages, List<ToolDefinition> tools,
                                   Consumer<String> onToken) {
        return doGenerate(messages, tools, true);
    }

    private Message doGenerate(List<Message> messages, List<ToolDefinition> tools,
                                boolean streaming) {
        callCount++;
        if (index >= steps.size()) {
            throw new LLMException(SCRIPT_EXHAUSTED + ": script exhausted at call " + callCount);
        }

        var step = steps.get(index++);
        validateStep(step, tools, streaming);

        if (step.error() != null) {
            throw step.error();
        }
        return step.response();
    }

    private void validateStep(ScriptedStep step, List<ToolDefinition> tools, boolean streaming) {
        // phase 校验
        String actualPhase = tools.isEmpty() ? "phase1" : "phase2";
        if (step.phase() != null && !step.phase().equals(actualPhase)) {
            throw new LLMException(SCRIPT_PHASE_MISMATCH
                + ": expected=" + step.phase() + " actual=" + actualPhase
                + " at call " + callCount);
        }

        // streaming 校验
        if (step.expectedStreaming() != streaming) {
            throw new LLMException(SCRIPT_PHASE_MISMATCH
                + ": expectedStreaming=" + step.expectedStreaming()
                + " actual=" + streaming + " at call " + callCount);
        }

        // availableTools 校验
        if (step.expectedAvailableTools() != null) {
            Set<String> actualTools = tools.stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());
            if (!step.expectedAvailableTools().equals(actualTools)) {
                throw new LLMException(SCRIPT_TOOLS_MISMATCH
                    + ": expected=" + step.expectedAvailableTools()
                    + " actual=" + actualTools + " at call " + callCount);
            }
        }
    }

    // ── Diagnostics ──────────────────────────────────────────────────

    /** 是否已消费全部步骤 */
    public boolean isExhausted() {
        return index >= steps.size();
    }

    /** 是否所有步骤都已消费（用于 post-run 验证） */
    public boolean allStepsConsumed() {
        return index == steps.size();
    }

    /** 已消费的步骤数 */
    public int consumedSteps() {
        return index;
    }

    /** 总步骤数 */
    public int totalSteps() {
        return steps.size();
    }

    /** 总 generate 调用次数 */
    public int callCount() {
        return callCount;
    }

    // ── Builder ──────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<ScriptedStep> steps = new ArrayList<>();

        public Builder step(ScriptedStep step) {
            steps.add(step);
            return this;
        }

        public Builder text(String phase, Integer turn, boolean streaming,
                            Set<String> tools, String content) {
            steps.add(ScriptedStep.text(phase, turn, streaming, tools, content));
            return this;
        }

        public Builder text(String content) {
            steps.add(ScriptedStep.text(content));
            return this;
        }

        public Builder toolCall(Message toolCallMessage) {
            steps.add(ScriptedStep.toolCall(toolCallMessage));
            return this;
        }

        public Builder error(RuntimeException e) {
            steps.add(ScriptedStep.error(e));
            return this;
        }

        public ScriptedProvider build() {
            return new ScriptedProvider(List.copyOf(steps));
        }
    }
}
