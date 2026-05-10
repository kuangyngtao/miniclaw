package com.miniclaw.engine.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.engine.ThinkingMode;
import com.miniclaw.provider.LLMConfig;
import com.miniclaw.provider.impl.openai.OpenAIProvider;
import com.miniclaw.tools.ToolRegistry;
import com.miniclaw.tools.impl.ReadTool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ReadTool 端到端集成测试。
 * 创建真实文件 → 挂载 ReadTool → 启动引擎 → LLM 读取文件内容并回答。
 *
 * 运行: mvn test -pl miniclaw-engine -Dtest=ReadToolIntegrationTest -DapiKey=xxx
 */
public class ReadToolIntegrationTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        // 0. 获取 API Key（按优先级尝试多个来源）
        String apiKey = System.getenv("MINICLAW-DS-API");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("MINICLAW_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("ANTHROPIC_AUTH_TOKEN");
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("DEEPSEEK_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getProperty("apiKey");
        }
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("请设置 MINICLAW-DS-API 环境变量或 -DapiKey=xxx");
            System.exit(1);
        }

        // 1. 创建工作区 + hello.txt
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "miniclaw-read-test");
        Files.createDirectories(workDir);

        Path helloFile = workDir.resolve("hello.txt");
        String helloContent = "Hello, go-tiny-claw 引擎！我是来自物理文件系统的一段神秘文本。大模型今天终于看到了我！";
        Files.writeString(helloFile, helloContent, StandardCharsets.UTF_8);
        System.out.println("=== 已创建测试文件: " + helloFile + " ===\n");

        // 2. 挂载工具
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadTool(workDir));

        // 3. 实例化引擎（关闭慢思考）
        LLMConfig config = LLMConfig.builder()
            .apiKey(apiKey)
            .baseUrl("https://api.deepseek.com")
            .model("deepseek-chat")
            .build();

        OpenAIProvider provider = new OpenAIProvider(config);

        AgentEngine engine = new AgentEngine(provider, registry,
            workDir.toString(), ThinkingMode.OFF);

        // 4. 下发任务：必须读取 hello.txt 才能回答
        String prompt = args.length > 0
            ? String.join(" ", args)
            : "请读取 hello.txt 文件，然后一字不差地告诉我文件中写了什么内容。";

        System.out.println("提示词: " + prompt);
        System.out.println("\n--- ReAct Loop 开始 (ThinkingMode=OFF) ---\n");

        String result = engine.run(prompt);

        System.out.println("\n--- 最终结果 ---");
        System.out.println(result);

        // 5. 清理
        Files.deleteIfExists(helloFile);
        Files.deleteIfExists(workDir);
    }
}
