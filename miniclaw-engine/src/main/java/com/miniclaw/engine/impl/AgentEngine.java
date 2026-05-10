package com.miniclaw.engine.impl;

import com.miniclaw.engine.AgentLoop;
import com.miniclaw.engine.ThinkingMode;
import com.miniclaw.provider.LLMException;
import com.miniclaw.provider.LLMProvider;
import com.miniclaw.tools.Registry;
import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.ToolCall;
import com.miniclaw.tools.schema.ToolDefinition;
import com.miniclaw.tools.schema.ToolResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentEngine — miniclaw 的核心驱动。
 * 组装 LLMProvider + Registry，驱动 ReAct 循环。
 * 支持两种思考模式：OFF（单阶段）和 TWO_STAGE（先规划再执行）。
 */
public class AgentEngine implements AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    private static final String SYSTEM_PROMPT =
        "You are miniclaw, an expert coding assistant. "
        + "You have full access to tools in the workspace. "
        + "Think step by step before acting.";

    private static final List<ToolDefinition> EMPTY_TOOLS = Collections.emptyList();

    private final LLMProvider provider;
    private final Registry registry;
    private final String workDir;
    private final ThinkingMode thinkingMode;
    private final Consumer<String> onReasoning;

    public AgentEngine(LLMProvider provider, Registry registry, String workDir) {
        this(provider, registry, workDir, ThinkingMode.OFF, null);
    }

    public AgentEngine(LLMProvider provider, Registry registry, String workDir, ThinkingMode thinkingMode) {
        this(provider, registry, workDir, thinkingMode, null);
    }

    public AgentEngine(LLMProvider provider, Registry registry, String workDir,
                       ThinkingMode thinkingMode, Consumer<String> onReasoning) {
        this.provider = provider;
        this.registry = registry;
        this.workDir = workDir;
        this.thinkingMode = thinkingMode;
        this.onReasoning = onReasoning;
    }

    @Override
    public String run(String userPrompt) {
        log.info("[Engine] 引擎启动，锁定工作区: {}, 思考模式: {}", workDir, thinkingMode);

        List<Message> contextHistory = new ArrayList<>();
        contextHistory.add(Message.system(SYSTEM_PROMPT));
        contextHistory.add(Message.user(userPrompt));

        int turnCount = 0;

        while (true) {
            turnCount++;
            log.info("========== [Turn {}] 开始 ==========", turnCount);

            // === 两阶段慢思考：第一阶段 — 剥离工具，强制推理规划 ===
            if (thinkingMode == ThinkingMode.TWO_STAGE) {
                log.info("[Engine] 慢思考阶段1: 剥离工具，强制推理规划...");
                try {
                    Message thinkingMsg = provider.generate(contextHistory, EMPTY_TOOLS);
                    contextHistory.add(thinkingMsg);

                    if (thinkingMsg.content() != null && !thinkingMsg.content().isEmpty()) {
                        log.debug("[推理] {}", thinkingMsg.content());
                        if (onReasoning != null) {
                            onReasoning.accept(thinkingMsg.content());
                        }
                    }
                    log.info("[Engine] 慢思考阶段1 完成，规划已注入上下文");
                } catch (LLMException e) {
                    log.error("[Engine] 慢思考阶段1 失败: {}", e.getMessage());
                    return "[A-002] 慢思考规划阶段 LLM 调用失败: " + e.getMessage();
                }
            }

            // === 第二阶段 / 普通模式：带工具推理 ===
            List<ToolDefinition> availableTools = registry.getAvailableTools();
            if (thinkingMode == ThinkingMode.TWO_STAGE) {
                log.info("[Engine] 慢思考阶段2: 带工具执行 ({})...", availableTools.size());
            } else {
                log.info("[Engine] 正在思考 (Reasoning)...");
            }

            final Message responseMsg;
            try {
                responseMsg = provider.generate(contextHistory, availableTools);
            } catch (LLMException e) {
                log.error("[Engine] LLM 调用失败 (A-002): {}", e.getMessage());
                return "[A-002] LLM 调用失败: " + e.getMessage();
            }
            contextHistory.add(responseMsg);

            if (responseMsg.content() != null && !responseMsg.content().isEmpty()) {
                log.debug("Model response: {} chars", responseMsg.content().length());
            }

            // 退出条件：没有工具调用
            if (responseMsg.toolCalls() == null || responseMsg.toolCalls().isEmpty()) {
                log.info("[Engine] 任务完成，退出循环。");
                return responseMsg.content() != null ? responseMsg.content() : "";
            }

            // Action + Observation
            log.info("[Engine] 模型请求调用 {} 个工具...", responseMsg.toolCalls().size());

            for (ToolCall toolCall : responseMsg.toolCalls()) {
                log.info("  -> 执行工具: {}, 参数: {}", toolCall.name(), toolCall.arguments());

                ToolResult result = registry.execute(toolCall);

                if (result.isError()) {
                    log.info("  -> 工具执行报错: {}", result.output());
                } else {
                    log.info("  -> 工具执行成功 (返回 {} 字节)", result.output().length());
                }

                contextHistory.add(Message.toolResult(toolCall.id(), result.output()));
            }
        }
    }

    public String workDir() {
        return workDir;
    }

    public ThinkingMode thinkingMode() {
        return thinkingMode;
    }
}
