package com.miniclaw.provider.impl.openai;

import com.miniclaw.provider.LLMConfig;
import com.miniclaw.provider.LLMProvider;
import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.ToolDefinition;
import java.util.List;

/**
 * DeepSeek V4 端到端连通性验证。
 *
 * 从环境变量 MINICLAW-DS-API 或系统属性 apiKey 读取密钥。
 * 运行: mvn exec:java -pl miniclaw-provider \
 *   -Dexec.classpathScope=test \
 *   -Dexec.mainClass=com.miniclaw.provider.impl.openai.DeepSeekConnectivityTest
 */
public class DeepSeekConnectivityTest {

    public static void main(String[] args) {
        String apiKey = System.getenv("MINICLAW-DS-API");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("MINICLAW_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getProperty("apiKey");
        }
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("请设置 MINICLAW-DS-API 环境变量或 -DapiKey=xxx");
            System.exit(1);
        }

        LLMConfig config = LLMConfig.builder()
            .apiKey(apiKey)
            .baseUrl("https://api.deepseek.com")
            .model("deepseek-chat")
            .build();

        LLMProvider provider = new OpenAIProvider(config);

        // Test 1: 纯文本对话
        System.out.println("=== Test 1: 纯文本对话 ===");
        List<Message> messages = List.of(
            Message.system("用中文简短回答，不超过一句话。"),
            Message.user("请用一句话介绍你自己"));
        Message response = provider.generate(messages, List.of());
        System.out.println("回复: " + response.content());
        System.out.println();

        // Test 2: 带工具定义的对话（让模型决定是否调用工具）
        System.out.println("=== Test 2: 带工具定义 ===");
        List<ToolDefinition> tools = List.of(
            new ToolDefinition("read_file", "读取指定路径的文件内容",
                new java.util.LinkedHashMap<String, Object>() {{
                    put("type", "object");
                    put("properties", new java.util.LinkedHashMap<String, Object>() {{
                        put("path", new java.util.LinkedHashMap<String, Object>() {{
                            put("type", "string");
                            put("description", "文件路径");
                        }});
                    }});
                    put("required", List.of("path"));
                }}));
        Message response2 = provider.generate(
            List.of(Message.user("读取 /tmp/test.txt 的内容")), tools);
        if (response2.toolCalls() != null && !response2.toolCalls().isEmpty()) {
            System.out.println("模型请求调用工具:");
            response2.toolCalls().forEach(tc ->
                System.out.printf("  - %s(%s)%n", tc.name(), tc.arguments()));
        } else {
            System.out.println("回复: " + response2.content());
        }

        System.out.println("\n=== DeepSeek V4 连通验证通过 ===");
    }
}
