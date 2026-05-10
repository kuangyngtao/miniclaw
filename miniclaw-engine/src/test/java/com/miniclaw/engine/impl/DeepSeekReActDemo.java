package com.miniclaw.engine.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.engine.ThinkingMode;
import com.miniclaw.provider.LLMConfig;
import com.miniclaw.provider.impl.openai.OpenAIProvider;
import com.miniclaw.tools.Result;
import com.miniclaw.tools.Tool;
import com.miniclaw.tools.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * DeepSeek V4 端到端 ReAct 演示。
 * 使用真实 API + 本地文件系统工具，跑完整的两阶段慢思考循环。
 *
 * 运行: mvn exec:java -pl miniclaw-engine \
 *   -Dexec.classpathScope=test \
 *   -Dexec.mainClass=com.miniclaw.engine.impl.DeepSeekReActDemo
 */
public class DeepSeekReActDemo {

    private static final ObjectMapper mapper = new ObjectMapper();

    // === 工具实现 ===

    /** 列出当前目录文件 */
    static class ListFilesTool implements Tool {
        private final Path workDir;

        ListFilesTool(Path workDir) {
            this.workDir = workDir;
        }

        @Override
        public String name() { return "list_files"; }

        @Override
        public String description() {
            return "列出当前工作目录下的文件和子目录。无需参数。";
        }

        @Override
        public String inputSchema() {
            return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
        }

        @Override
        public Result<String> execute(String arguments) {
            try {
                StringBuilder sb = new StringBuilder();
                Files.list(workDir).sorted().forEach(p -> {
                    String type = Files.isDirectory(p) ? "d" : "-";
                    String name = p.getFileName().toString();
                    try {
                        long size = Files.isDirectory(p) ? 0 : Files.size(p);
                        sb.append(String.format("%s %-40s %8d bytes%n", type, name, size));
                    } catch (IOException e) {
                        sb.append(String.format("%s %-40s (error)%n", type, name));
                    }
                });
                return new Result.Ok<>(sb.length() > 0 ? sb.toString() : "(空目录)");
            } catch (IOException e) {
                return new Result.Err<>(new Result.ErrorInfo("T-005", e.getMessage()));
            }
        }
    }

    /** 读取指定文件内容 */
    static class ReadFileTool implements Tool {
        private final Path workDir;

        ReadFileTool(Path workDir) {
            this.workDir = workDir;
        }

        @Override
        public String name() { return "read_file"; }

        @Override
        public String description() {
            return "读取指定文件的内容。参数: path (相对于当前目录的文件路径)";
        }

        @Override
        public String inputSchema() {
            return "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"文件路径\"}},"
                + "\"required\":[\"path\"]}";
        }

        @Override
        public Result<String> execute(String arguments) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> args = mapper.readValue(arguments, Map.class);
                String relPath = args.get("path");
                if (relPath == null || relPath.isBlank()) {
                    return new Result.Err<>(new Result.ErrorInfo("T-002", "缺少 path 参数"));
                }
                Path file = workDir.resolve(relPath).normalize();
                if (!file.startsWith(workDir)) {
                    return new Result.Err<>(new Result.ErrorInfo("T-003", "路径越权: " + relPath));
                }
                String content = Files.readString(file);
                int maxLen = 4000;
                if (content.length() > maxLen) {
                    content = content.substring(0, maxLen) + "\n...(truncated)";
                }
                return new Result.Ok<>(content);
            } catch (JsonProcessingException e) {
                return new Result.Err<>(new Result.ErrorInfo("T-002", "参数 JSON 解析失败"));
            } catch (IOException e) {
                return new Result.Err<>(new Result.ErrorInfo("T-005", e.getMessage()));
            }
        }
    }

    // === 入口 ===

    public static void main(String[] args) {
        // 1. 获取 API Key
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

        // 2. 工作目录
        Path workDir = Path.of(System.getProperty("user.dir"));
        System.out.println("=== DeepSeek ReAct 端到端演示 ===");
        System.out.println("工作目录: " + workDir);
        System.out.println("思考模式: TWO_STAGE\n");

        // 3. 组装组件
        LLMConfig config = LLMConfig.builder()
            .apiKey(apiKey)
            .baseUrl("https://api.deepseek.com")
            .model("deepseek-chat")
            .build();

        OpenAIProvider provider = new OpenAIProvider(config);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ListFilesTool(workDir));
        registry.register(new ReadFileTool(workDir));

        AgentEngine engine = new AgentEngine(provider, registry,
            workDir.toString(), ThinkingMode.TWO_STAGE);

        // 4. 执行任务
        String prompt = args.length > 0
            ? String.join(" ", args)
            : "列出当前目录的文件，并读取 CLAUDE.md 的内容，然后告诉我这个项目是做什么的";

        System.out.println("提示词: " + prompt);
        System.out.println("\n--- ReAct Loop 开始 ---\n");

        String result = engine.run(prompt);

        System.out.println("\n--- 最终结果 ---");
        System.out.println(result);
    }
}
