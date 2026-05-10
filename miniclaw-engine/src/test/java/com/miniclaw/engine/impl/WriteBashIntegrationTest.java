package com.miniclaw.engine.impl;

import com.miniclaw.engine.ThinkingMode;
import com.miniclaw.provider.LLMConfig;
import com.miniclaw.provider.impl.openai.OpenAIProvider;
import com.miniclaw.tools.ToolRegistry;
import com.miniclaw.tools.impl.BashTool;
import com.miniclaw.tools.impl.ReadTool;
import com.miniclaw.tools.impl.WriteTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WriteTool + BashTool 端到端集成测试。
 * 三个验证点：bash 查版本、write 写母亲节作文、write + bash 编译运行 HelloWorld。
 *
 * 运行: mvn exec:java -pl miniclaw-engine -Dexec.classpathScope=test
 *   -Dexec.mainClass=com.miniclaw.engine.impl.WriteBashIntegrationTest
 */
public class WriteBashIntegrationTest {

    public static void main(String[] args) throws IOException {
        // 0. API Key
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
            System.err.println("请设置 API Key 环境变量");
            System.exit(1);
        }

        // 1. 创建测试工作区
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "miniclaw-write-bash-test");
        if (Files.exists(workDir)) {
            // 清理上次残留
            try (var s = Files.walk(workDir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
        Files.createDirectories(workDir);
        System.out.println("=== 测试工作区: " + workDir + " ===\n");

        // 2. 挂载工具
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadTool(workDir));
        registry.register(new WriteTool(workDir));
        registry.register(new BashTool(workDir));
        System.out.println("已注册工具: read, write, bash\n");

        // 3. 实例化引擎（关闭慢思考）
        LLMConfig config = LLMConfig.builder()
            .apiKey(apiKey)
            .baseUrl("https://api.deepseek.com")
            .model("deepseek-chat")
            .build();

        OpenAIProvider provider = new OpenAIProvider(config);
        AgentEngine engine = new AgentEngine(provider, registry,
            workDir.toString(), ThinkingMode.OFF);

        // 4. 下发任务
        String prompt = args.length > 0 ? String.join(" ", args) : """
            请按顺序完成以下三个任务：

            任务1：使用 bash 工具分别执行以下命令，用中文告诉我结果：
              - 如果环境中有 claude 或 ClaudeCode 命令，执行并查看版本
              - 执行 nvcc --version 或 nvidia-smi 查看 CUDA 版本

            任务2：使用 write 工具在当前目录创建一个文件 mother-day.txt，
            写一篇关于母亲节的短文（300字左右），包含真情实感。

            任务3：在当前目录创建一个 HelloWorld.java 文件，然后使用 bash 工具
            编译并运行它，确认能正常输出。""";

        System.out.println("提示词:\n" + prompt);
        System.out.println("\n--- ReAct Loop 开始 (ThinkingMode=OFF) ---\n");

        String result = engine.run(prompt);

        System.out.println("\n--- 最终结果 ---");
        System.out.println(result);

        // 5. 列出生成的文件
        System.out.println("\n--- 工作区生成的文件 ---");
        Files.list(workDir).sorted().forEach(p ->
            System.out.println("  " + (Files.isDirectory(p) ? "d" : "-") + " " + p.getFileName()));
    }
}
