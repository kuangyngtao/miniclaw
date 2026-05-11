package com.miniclaw.engine.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.engine.ThinkingMode;
import com.miniclaw.provider.LLMProvider;
import com.miniclaw.tools.Registry;
import com.miniclaw.tools.Tool;
import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.Role;
import com.miniclaw.tools.schema.ToolCall;
import com.miniclaw.tools.schema.ToolDefinition;
import com.miniclaw.tools.schema.ToolResult;
import java.util.List;
import java.util.Optional;

/**
 * 慢思考两阶段模式独立 Demo。
 * 用 Mock 组件跑通完整的 TWO_STAGE ReAct 循环，打印全量日志。
 *
 * 运行: mvn exec:java -pl miniclaw-engine \
 *   -Dexec.classpathScope=test \
 *   -Dexec.mainClass=com.miniclaw.engine.impl.SlowThinkingDemo
 */
public class SlowThinkingDemo {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Mock LLMProvider — 两层智能：
     * Layer 1: availableTools 是否为空 → 判断 Phase 1（推理）还是 Phase 2（行动）
     * Layer 2: Phase 1 内部按轮次产生不同的推理内容
     *   Turn 1 → 规划
     *   Turn 2 → 基于工具结果的分析判断
     */
    static class DemoProvider implements LLMProvider {
        int phase1Count = 0;
        int phase2Count = 0;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            // Phase 1: 工具列表为空 → Thinking 阶段，强制纯文本推理
            if (availableTools.isEmpty()) {
                phase1Count++;
                if (phase1Count == 1) {
                    // 第一轮推理：制定计划
                    return Message.assistant(
                        "【推理中】目标是检查文件。我不能直接盲猜，"
                        + "需要先调用 bash 工具执行 ls 命令，"
                        + "看看当前目录下有什么，然后再做定夺。");
                }
                // 第二轮推理：基于上一轮的工具结果分析，判断是否还需要行动
                return Message.assistant(
                    "【推理中】上一轮已经拿到 ls 的输出，"
                    + "能看到 main.go 存在。"
                    + "用户要的就是检查文件，任务已完成，无需再调用工具。");
            }

            // Phase 2: 工具列表非空 → Action 阶段
            phase2Count++;
            if (phase2Count == 1) {
                JsonNode args = mapper.createObjectNode().put("command", "ls -la");
                return new Message(Role.ASSISTANT, "我要执行我刚才计划的步骤了。",
                    List.of(new ToolCall("call_123", "bash", args)), null);
            }

            return Message.assistant(
                "根据工具返回的结果，我看到了 main.go，任务圆满完成！");
        }
    }

    /** Mock Registry: 返回 bash 工具定义，执行返回模拟文件列表。 */
    static class DemoRegistry implements Registry {
        @Override
        public List<ToolDefinition> getAvailableTools() {
            return List.of(new ToolDefinition("bash", "execute shell command", null));
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.success(call.id(),
                "-rw-r--r--  1 user group  234 Oct 24 10:00 main.go\n");
        }

        @Override
        public void register(Tool tool) {}

        @Override
        public Optional<Tool> lookup(String name) {
            return Optional.empty();
        }

        @Override
        public boolean isReadOnly(String toolName) {
            return false; // bash is a write tool
        }
    }

    public static void main(String[] args) {
        System.out.println("=== SlowThinking Demo: TWO_STAGE 模式 ===\n");

        DemoProvider provider = new DemoProvider();
        DemoRegistry registry = new DemoRegistry();
        AgentEngine engine = new AgentEngine(provider, registry, "/tmp/work",
            ThinkingMode.TWO_STAGE);

        String result = engine.run("帮我检查当前目录的文件");

        System.out.println("\n=== 最终结果 ===");
        System.out.println(result);
        System.out.println("\n=== 统计: Phase1 " + provider.phase1Count + " 次 / Phase2 " + provider.phase2Count + " 轮 ===");
    }
}
