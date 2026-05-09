package com.miniclaw.engine.impl;

import com.miniclaw.engine.AgentLoop;
import com.miniclaw.provider.LLMProvider;
import com.miniclaw.tools.Registry;
import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.ToolCall;
import com.miniclaw.tools.schema.ToolDefinition;
import com.miniclaw.tools.schema.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentEngine — miniclaw 的核心驱动。
 * 组装 LLMProvider + Registry，驱动 ReAct 循环。
 */
public class AgentEngine implements AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    private static final String SYSTEM_PROMPT =
        "You are miniclaw, an expert coding assistant. "
        + "You have full access to tools in the workspace. "
        + "Think step by step before acting.";

    private final LLMProvider provider;
    private final Registry registry;
    private final String workDir;

    public AgentEngine(LLMProvider provider, Registry registry, String workDir) {
        this.provider = provider;
        this.registry = registry;
        this.workDir = workDir;
    }

    @Override
    public String run(String userPrompt) {
        log.info("[Engine] 引擎启动，锁定工作区: {}", workDir);

        // 1. 初始化会话上下文
        // TODO: 由动态 Prompt 组装器加载 AGENTS.md，目前硬编码
        List<Message> contextHistory = new ArrayList<>();
        contextHistory.add(Message.system(SYSTEM_PROMPT));
        contextHistory.add(Message.user(userPrompt));

        int turnCount = 0;

        // 2. The Main Loop: ReAct 循环
        while (true) {
            turnCount++;
            log.info("========== [Turn {}] 开始 ==========", turnCount);

            // 获取可用工具列表
            List<ToolDefinition> availableTools = registry.getAvailableTools();

            // 向大模型发起推理
            log.info("[Engine] 正在思考 (Reasoning)...");
            Message responseMsg = provider.generate(contextHistory, availableTools);

            // 将模型响应追加到上下文
            contextHistory.add(responseMsg);

            // 打印模型文本回复
            if (responseMsg.content() != null && !responseMsg.content().isEmpty()) {
                System.out.println("Model: " + responseMsg.content());
            }

            // 3. 退出条件：没有工具调用 → 任务完成
            if (responseMsg.toolCalls() == null || responseMsg.toolCalls().isEmpty()) {
                log.info("[Engine] 任务完成，退出循环。");
                return responseMsg.content() != null ? responseMsg.content() : "";
            }

            // 4. Action + Observation
            log.info("[Engine] 模型请求调用 {} 个工具...", responseMsg.toolCalls().size());

            for (ToolCall toolCall : responseMsg.toolCalls()) {
                log.info("  -> 执行工具: {}, 参数: {}", toolCall.name(), toolCall.arguments());

                ToolResult result = registry.execute(toolCall);

                if (result.isError()) {
                    log.info("  -> 工具执行报错: {}", result.output());
                } else {
                    log.info("  -> 工具执行成功 (返回 {} 字节)", result.output().length());
                }

                // 将工具执行结果包装为 user message 追加到上下文
                contextHistory.add(Message.toolResult(toolCall.id(), result.output()));
            }

            // 循环回到 THINK，模型带着新的 Observation 继续推理...
        }
    }

    public String workDir() {
        return workDir;
    }
}
