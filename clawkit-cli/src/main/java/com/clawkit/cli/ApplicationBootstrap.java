package com.clawkit.cli;

import com.clawkit.engine.SessionService;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.memory.impl.DiskMemoryService;
import com.clawkit.observability.FileRunRecorder;
import com.clawkit.observability.RunReader;
import com.clawkit.provider.LLMConfig;
import com.clawkit.provider.LLMProvider;
import com.clawkit.provider.ProviderFactory;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.context.SkillLoader;
import com.clawkit.tools.impl.*;
import com.clawkit.tools.mcp.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * 应用装配器。将 ClawkitApp 的 picocli 参数 + 配置组装为 ApplicationContext。
 */
public class ApplicationBootstrap {

    /**
     * 完整装配流程，返回包含所有依赖的 ApplicationContext。
     */
    public static ApplicationContext bootstrap(
            String model, String baseUrl, String protocol, boolean thinking, Path rootDir) {

        Path workDir = ApplicationBootstrap.resolveWorkDir(rootDir);

        // API Key
        String apiKey = readConfigValue("CLAWKIT_API_KEY", "ANTHROPIC_AUTH_TOKEN", "apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[C-003] CLAWKIT_API_KEY not set (env or ~/.clawkit/config.yaml).");
            System.exit(1);
        }

        // Protocol
        LLMConfig.Protocol protocolEnum;
        try {
            protocolEnum = LLMConfig.Protocol.valueOf(protocol.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("[C-004] Unknown protocol '" + protocol
                + "'. Valid: OPENAI_COMPAT, ANTHROPIC");
            System.exit(1);
            return null;
        }

        // Provider
        LLMConfig config = LLMConfig.builder()
            .apiKey(apiKey).baseUrl(baseUrl).model(model).protocol(protocolEnum).build();
        LLMProvider provider = ProviderFactory.create(config);

        // Tool registry
        Path userSkillsDir = Path.of(System.getProperty("user.home"), ".agents", "skills");
        ToolRegistry registry = createToolRegistry(workDir, userSkillsDir);

        // MCP
        McpConfig mcpConfig = McpConfig.load(workDir);
        if (!mcpConfig.servers().isEmpty()) {
            var servers = new LinkedHashMap<>(mcpConfig.servers());
            boolean hasInteractive = servers.values().stream().anyMatch(s -> !s.disabled());
            if (hasInteractive) {
                System.out.println();
                for (var entry : servers.entrySet()) {
                    var sc = entry.getValue();
                    if (sc.disabled()) continue;
                    System.out.print("  Enable MCP [" + sc.name() + "] ("
                        + sc.transport().name().toLowerCase() + ")? [Y/n] ");
                    String answer = System.console().readLine().trim().toLowerCase();
                    if ("n".equals(answer) || "no".equals(answer)) {
                        servers.put(entry.getKey(),
                            new McpServerConfig(sc.name(), sc.command(), sc.args(),
                                sc.url(), sc.env(), true));
                        System.out.println(ClawkitApp.GRAY + "    " + sc.name() + " disabled." + ClawkitApp.RESET);
                    }
                }
                System.out.println();
            }
            mcpConfig = new McpConfig(servers);
        }
        McpManager mcpManager = new McpManager();
        List<Tool> mcpTools = mcpManager.startAll(mcpConfig, workDir);
        for (Tool t : mcpTools) registry.register(t);

        // Memory
        ThinkingMode mode = thinking ? ThinkingMode.TWO_STAGE : ThinkingMode.OFF;
        Path memoryDir = Path.of(System.getProperty("user.home"), ".clawkit", "memory");
        DiskMemoryService memoryService = new DiskMemoryService(memoryDir);
        String memoryIndex = memoryService.loadIndex();

        // Engine
        String workspaceRules = ClawkitApp.loadWorkspaceRules(workDir);
        AgentEngine engine = new AgentEngine(provider, registry, workDir.toString(),
            mode, null, System.out::print, memoryIndex);
        engine.setWorkspaceRules(workspaceRules);

        // Skills
        Path projectSkillsDir = workDir.resolve(".clawkit").resolve("skills");
        SkillLoader skillLoader = new SkillLoader(userSkillsDir, projectSkillsDir);
        engine.setSkillLoader(skillLoader);
        engine.rebuildSkillCatalog(skillLoader.buildCatalog());
        engine.setDiskMemoryService(memoryService);

        // Observability
        Path clawkitDir = Path.of(System.getProperty("user.home"), ".clawkit");
        engine.addRecorder(new FileRunRecorder(clawkitDir));
        RunReader runReader = new RunReader(clawkitDir);

        // Sessions
        SessionService sessionService = new SessionService(
            clawkitDir.resolve("sessions"), provider);
        engine.setSessionService(sessionService);

        return new ApplicationContext(
            engine, provider, registry, sessionService, skillLoader,
            mcpManager, memoryService, runReader,
            null, // reader — 由 ClawkitApp 创建（需要 Terminal 初始化）
            new java.util.ArrayList<>(), // imChannels
            workDir, model, mode);
    }

    // ── 静态工具方法 ─────────────────────────────────────────────────

    /** 创建 ToolRegistry 并注册 8 个内置工具 + 安全拦截器 */
    static ToolRegistry createToolRegistry(Path workDir, Path... extraReadRoots) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadTool(workDir, extraReadRoots));
        registry.register(new WriteTool(workDir));
        registry.register(new TodoWriteTool());
        registry.register(new WebFetchTool());
        registry.register(new BashTool(workDir));
        registry.register(new EditTool(workDir));
        registry.register(new GlobTool(workDir));
        registry.register(new GrepTool(workDir));
        registry.addInterceptor(new CommandSafetyInterceptor());
        return registry;
    }

    private static String readConfigValue(String envKey, String altEnvKey, String... configPath) {
        String val = System.getenv(envKey);
        if (val != null && !val.isBlank()) return val;
        val = System.getenv(altEnvKey);
        if (val != null && !val.isBlank()) return val;
        Path configFile = Path.of(System.getProperty("user.home"), ".clawkit", "config.yaml");
        if (java.nio.file.Files.exists(configFile)) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var yamlMapper = new com.fasterxml.jackson.dataformat.yaml.YAMLFactory();
                var reader = mapper.readTree(
                    java.nio.file.Files.readString(configFile));
                for (String key : configPath) {
                    if (reader.has(key)) {
                        var node = reader.get(key);
                        return node.isTextual() ? node.asText() : node.toString();
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    static Path resolveWorkDir(Path rootDir) {
        if (rootDir == null) {
            return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
        Path path = rootDir.toAbsolutePath().normalize();
        try {
            if (!java.nio.file.Files.exists(path)) {
                java.nio.file.Files.createDirectories(path);
            }
        } catch (java.io.IOException e) {
            System.err.println("[C-001] Cannot create root directory: " + path);
            System.exit(1);
        }
        return path;
    }
}
