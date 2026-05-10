package com.miniclaw.engine.impl;

import com.miniclaw.engine.ThinkingMode;
import com.miniclaw.provider.LLMConfig;
import com.miniclaw.provider.impl.openai.OpenAIProvider;
import com.miniclaw.tools.ToolRegistry;
import com.miniclaw.tools.impl.BashTool;
import com.miniclaw.tools.impl.EditTool;
import com.miniclaw.tools.impl.ReadTool;
import com.miniclaw.tools.impl.WriteTool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * EditTool 四级容错端到端集成测试。
 * 验证：LLM 格式幻觉（换行符/缩进/空行偏差）被底层模糊匹配吸收。
 *
 * 运行: mvn compile exec:java -pl miniclaw-engine -Dexec.classpathScope=test
 *   -Dexec.mainClass=com.miniclaw.engine.impl.EditIntegrationTest
 */
public class EditIntegrationTest {

    private static final String SERVER_GO = """
        package main

        import (
        \t"fmt"
        \t"net/http"
        )

        type Server struct {
        \tport int
        }

        func (s *Server) Start() {
        \tfmt.Printf("server starting on port %d\\n", s.port)

        \t// TODO: 增加鉴权逻辑
        \tif r.Header.Get("Authorization") == "" {
        \t\tfmt.Println("please authenticate first")
        \t\thttp.Error(w, "Unauthorized", 401)
        \t\treturn
        \t}

        \tfmt.Println("server started successfully")
        }
        """;

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
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "miniclaw-edit-test");
        if (Files.exists(workDir)) {
            try (var s = Files.walk(workDir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
        Files.createDirectories(workDir);
        System.out.println("=== 测试工作区: " + workDir + " ===\n");

        // 写入 server.go
        Files.writeString(workDir.resolve("server.go"), SERVER_GO, StandardCharsets.UTF_8);
        System.out.println("--- server.go 原始内容 ---");
        System.out.println(SERVER_GO);
        System.out.println();

        // 2. 挂载工具
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadTool(workDir));
        registry.register(new WriteTool(workDir));
        registry.register(new EditTool(workDir));
        registry.register(new BashTool(workDir));
        System.out.println("已注册工具: read, write, edit, bash\n");

        // 3. 实例化引擎
        LLMConfig config = LLMConfig.builder()
            .apiKey(apiKey)
            .baseUrl("https://api.deepseek.com")
            .model("deepseek-chat")
            .build();

        OpenAIProvider provider = new OpenAIProvider(config);
        AgentEngine engine = new AgentEngine(provider, registry,
            workDir.toString(), ThinkingMode.OFF);

        // 4. 下发编辑任务
        String prompt = args.length > 0 ? String.join(" ", args) : """
            我当前目录下有一个 server.go 文件。
            请帮我把里面 "TODO: 增加鉴权逻辑" 下面的那个 if 语句，整个替换为：
            if user == nil {
                fmt.Println("Forbidden!")
                return
            }

            注意：请使用 edit 工具进行精准替换，不要 rewrite 整个文件。""";

        System.out.println("提示词:\n" + prompt);
        System.out.println("\n--- ReAct Loop 开始 (ThinkingMode=OFF) ---\n");

        String result = engine.run(prompt);

        System.out.println("\n--- 最终结果 ---");
        System.out.println(result);

        // 5. 验证 — 读回 server.go
        System.out.println("\n--- 修改后的 server.go ---");
        String modified = Files.readString(workDir.resolve("server.go"), StandardCharsets.UTF_8);
        System.out.println(modified);

        // 断言检查
        System.out.println("\n--- 验证 ---");
        boolean hasForbidden = modified.contains("Forbidden!");
        boolean hasNilCheck = modified.contains("user == nil");
        boolean oldCodeGone = !modified.contains("please authenticate first");
        System.out.println("  包含 'Forbidden!'   : " + (hasForbidden ? "PASS" : "FAIL"));
        System.out.println("  包含 'user == nil'  : " + (hasNilCheck ? "PASS" : "FAIL"));
        System.out.println("  旧代码已移除        : " + (oldCodeGone ? "PASS" : "FAIL"));

        // 6. 列出文件
        System.out.println("\n--- 工作区文件 ---");
        Files.list(workDir).sorted().forEach(p ->
            System.out.println("  " + (Files.isDirectory(p) ? "d" : "-") + " " + p.getFileName()));
    }
}
