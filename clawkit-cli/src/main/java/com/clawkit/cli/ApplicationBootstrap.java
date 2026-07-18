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
import java.time.Duration;

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

        ResolvedConfiguration resolved = ConfigResolver.resolve(model, baseUrl, protocol,
            thinking, rootDir, System.getenv(), Path.of(System.getProperty("user.home")));
        return bootstrap(resolved);
    }

    static ApplicationContext bootstrap(ResolvedConfiguration resolved) {
        EffectiveConfig effective = resolved.effective();

        Path workDir = ApplicationBootstrap.resolveWorkDir(effective.rootDir());

        // API Key
        String apiKey = resolved.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ConfigurationException("C-003", "CLAWKIT_API_KEY is not set",
                "clawkit was not started",
                "In PowerShell run: $env:CLAWKIT_API_KEY = '<your-deepseek-key>'");
        }

        // Protocol
        LLMConfig.Protocol protocolEnum;
        try {
            protocolEnum = LLMConfig.Protocol.valueOf(effective.protocol().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("C-004", "Unsupported protocol: " + effective.protocol(),
                "the provider was not created", "Use OPENAI_COMPAT for DeepSeek.");
        }

        // Provider
        LLMConfig config = LLMConfig.builder()
            .apiKey(apiKey).baseUrl(effective.baseUrl()).model(effective.model())
            .protocol(protocolEnum)
            .requestTimeout(Duration.ofSeconds(effective.requestTimeoutSeconds()))
            .maxRetries(effective.maxRetries())
            .build();
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
                    if (System.console() == null) {
                        throw new ConfigurationException("C-007",
                            "Interactive terminal required to approve MCP server '" + sc.name() + "'",
                            "MCP servers were not started",
                            "Run from a terminal or use docker run -it; otherwise disable the server.");
                    }
                    String line = System.console().readLine();
                    String answer = line == null ? "n" : line.trim().toLowerCase();
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
        ThinkingMode mode = effective.thinking() ? ThinkingMode.TWO_STAGE : ThinkingMode.OFF;
        Path memoryDir = Path.of(System.getProperty("user.home"), ".clawkit", "memory");
        DiskMemoryService memoryService = new DiskMemoryService(memoryDir);
        String memoryIndex = memoryService.loadIndex();

        // Engine — PR-2: AgentRuntimeDependencies 构造
        String workspaceRules = ClawkitApp.loadWorkspaceRules(workDir);

        // Runtime integrations are created before the engine so production does not
        // rely on post-construction setters.
        Path projectSkillsDir = workDir.resolve(".clawkit").resolve("skills");
        SkillLoader skillLoader = new SkillLoader(userSkillsDir, projectSkillsDir);
        var skillRuntime = new com.clawkit.engine.impl.DefaultSkillRuntime(skillLoader);

        // PR-9: 统一 recorder — FileRunRecorder 加入共享 CompositeRunRecorder
        Path clawkitDir = Path.of(System.getProperty("user.home"), ".clawkit");
        var sharedRecorder = new com.clawkit.observability.CompositeRunRecorder(
            new com.clawkit.observability.FileRunRecorder(clawkitDir));
        var gateway = new com.clawkit.engine.impl.ObservingProviderGateway(provider, sharedRecorder);
        var memoryHooks = new com.clawkit.engine.impl.DefaultMemoryHooks(
            memoryService, gateway, provider.getContextWindow(), provider.getEncoding());
        var deps = new com.clawkit.engine.AgentRuntimeDependencies(
            gateway, null, registry,
            provider.getContextWindow(), provider.getEncoding(),
            sharedRecorder, memoryHooks, skillRuntime);
        AgentEngine engine = new AgentEngine(deps, workDir.toString(), mode, memoryIndex);
        engine.setWorkspaceRules(workspaceRules);
        // contextPipeline 已由构造器自建，无需手动 init

        // Skills are owned by SkillRuntime; only its catalog is refreshed here.
        engine.rebuildSkillCatalog();

        // Observability
        RunReader runReader = new RunReader(clawkitDir);

        // Sessions (PR-12: SessionStore 接口)
        var sessionStore = new com.clawkit.engine.impl.FileSessionStore(
            clawkitDir.resolve("sessions"));
        SessionService sessionService = new SessionService(sessionStore);
        sessionService.setProviderGateway(gateway);  // Session 摘要经 gateway
        engine.setSessionService(sessionService);

        return new ApplicationContext(
            engine, provider, registry, sessionService, skillLoader,
            mcpManager, memoryService, runReader,
            null, // reader — 由 ClawkitApp 创建（需要 Terminal 初始化）
            new java.util.ArrayList<>(), // imChannels
            workDir, effective.model(), mode, effective);
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
        registry.register(new GitReadTool(workDir));
        registry.register(new EditTool(workDir));
        registry.register(new GlobTool(workDir));
        registry.register(new GrepTool(workDir));
        registry.addInterceptor(new CommandSafetyInterceptor());
        return registry;
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
            throw new ConfigurationException("C-001", "Cannot create root directory: " + path,
                "clawkit was not started", "Choose an existing writable directory with --root.");
        }
        return path;
    }
}
