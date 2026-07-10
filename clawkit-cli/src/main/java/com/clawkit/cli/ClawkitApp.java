package com.clawkit.cli;

import com.clawkit.context.SkillCatalog;
import com.clawkit.context.SkillLoader;
import com.clawkit.engine.ApprovalHandler;
import com.clawkit.engine.ApprovalRequest;
import com.clawkit.engine.ApprovalResult;
import com.clawkit.engine.ExecutionMode;
import com.clawkit.engine.PermissionMode;
import com.clawkit.engine.RiskLevel;
import com.clawkit.engine.SessionMeta;
import com.clawkit.engine.SessionService;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.engine.AgentState;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.engine.impl.PlanParser;
import com.clawkit.tools.schema.ExecutionPlan;
import com.clawkit.tools.schema.Task;
import com.clawkit.im.ImChannel;
import com.clawkit.im.ImChannelStatus;
import com.clawkit.im.ConfigHelper;
import com.clawkit.im.feishu.FeishuChannel;
import com.clawkit.im.feishu.FeishuConfig;
import com.clawkit.im.weixin.WeixinChannel;
import com.clawkit.im.weixin.WeixinConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawkit.memory.MemoryEntry;
import com.clawkit.memory.MemoryType;
import com.clawkit.memory.impl.DiskMemoryService;
import com.clawkit.provider.LLMConfig;
import com.clawkit.provider.LLMProvider;
import com.clawkit.provider.ProviderFactory;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.impl.BashTool;
import com.clawkit.tools.impl.CommandSafetyInterceptor;
import com.clawkit.tools.impl.EditTool;
import com.clawkit.tools.impl.GlobTool;
import com.clawkit.tools.impl.GrepTool;
import com.clawkit.tools.impl.ReadTool;
import com.clawkit.tools.impl.TodoWriteTool;
import com.clawkit.tools.impl.WebFetchTool;
import com.clawkit.tools.impl.WriteTool;
import com.clawkit.tools.mcp.McpConfig;
import com.clawkit.tools.mcp.McpManager;
import com.clawkit.tools.mcp.McpServerConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "clawkit",
    description = "A minimal AI coding assistant",
    mixinStandardHelpOptions = true,
    version = "clawkit 0.1.0"
)
public class ClawkitApp implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClawkitApp.class);

    @Option(names = {"-m", "--model"}, defaultValue = "deepseek-chat",
        description = "Model name (default: deepseek-chat)")
    private String model;

    @Option(names = {"--base-url"}, defaultValue = "https://api.deepseek.com",
        description = "API base URL")
    private String baseUrl;

    @Option(names = {"--protocol"}, defaultValue = "OPENAI_COMPAT",
        description = "API protocol: OPENAI_COMPAT, ANTHROPIC")
    private String protocol;

    @Option(names = {"--thinking"}, description = "Enable slow thinking mode (TWO_STAGE)")
    private boolean thinking;

    @Option(names = {"--im"}, description = "Start in IM bot mode: feishu|weixin")
    private String imMode;

    @Option(names = {"--root"}, description = "Restrict working directory (default: current dir)")
    private Path rootDir;

    private Path resolvedWorkDir;
    private AgentEngine engine;
    private SessionService sessionService;
    private final List<ImChannel> imChannels = new java.util.ArrayList<>();
    private SkillLoader skillLoader;
    private McpManager mcpManager;
    private ToolRegistry registry;
    private LLMConfig.Protocol protocolEnum;
    private LineReader reader;
    private com.clawkit.observability.RunReader runReader;

    @Override
    public void run() {
        if (imMode != null && !imMode.isBlank()) {
            startImBot(imMode.strip().toLowerCase());
            return;
        }

        resolvedWorkDir = resolveWorkDir(rootDir);

        String apiKey = readConfigValue("CLAWKIT_API_KEY", "ANTHROPIC_AUTH_TOKEN", "apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[C-003] CLAWKIT_API_KEY not set (env or ~/.clawkit/config.yaml).");
            System.exit(1);
        }

        try {
            protocolEnum = LLMConfig.Protocol.valueOf(protocol.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("[C-004] Unknown protocol '" + protocol
                + "'. Valid: OPENAI_COMPAT, ANTHROPIC");
            System.exit(1);
            return; // unreachable, but javac doesn't know that
        }

        LLMConfig config = LLMConfig.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .model(model)
            .protocol(protocolEnum)
            .build();

        LLMProvider provider = ProviderFactory.create(config);

        Path userSkillsDir = Path.of(System.getProperty("user.home"), ".agents", "skills");
        registry = createToolRegistry(resolvedWorkDir, userSkillsDir);

        McpConfig mcpConfig = McpConfig.load(resolvedWorkDir);
        if (!mcpConfig.servers().isEmpty()) {
            var servers = new java.util.LinkedHashMap<>(mcpConfig.servers());
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
                        System.out.println(GRAY + "    " + sc.name() + " disabled." + RESET);
                    }
                }
                System.out.println();
            }
            mcpConfig = new McpConfig(servers);
        }
        mcpManager = new McpManager();
        List<Tool> mcpTools = mcpManager.startAll(mcpConfig, resolvedWorkDir);
        for (Tool t : mcpTools) registry.register(t);

        ThinkingMode mode = thinking ? ThinkingMode.TWO_STAGE : ThinkingMode.OFF;
        Path memoryDir = Path.of(System.getProperty("user.home"), ".clawkit", "memory");
        DiskMemoryService memoryService = new DiskMemoryService(memoryDir);
        String memoryIndex = memoryService.loadIndex();

        // 读取分级 CLAUDE.md（~/.clawkit/CLAUDE.md + ./CLAUDE.md）
        String workspaceRules = loadWorkspaceRules(resolvedWorkDir);

        engine = new AgentEngine(provider, registry, resolvedWorkDir.toString(),
            mode, null, System.out::print, memoryIndex);
        engine.setWorkspaceRules(workspaceRules);

        // 初始化 Skills 系统
        Path projectSkillsDir = resolvedWorkDir.resolve(".clawkit").resolve("skills");
        skillLoader = new SkillLoader(userSkillsDir, projectSkillsDir);
        engine.setSkillLoader(skillLoader);
        engine.rebuildSkillCatalog(skillLoader.buildCatalog());
        engine.setDiskMemoryService(memoryService);

        // 初始化观测系统
        Path clawkitDir = Path.of(System.getProperty("user.home"), ".clawkit");
        var fileRunRecorder = new com.clawkit.observability.FileRunRecorder(clawkitDir);
        engine.onRunEvent(fileRunRecorder);
        runReader = new com.clawkit.observability.RunReader(clawkitDir);

        Path sessionsDir = clawkitDir.resolve("sessions");
        sessionService = new SessionService(sessionsDir, provider);
        engine.setSessionService(sessionService);

        if (mode == ThinkingMode.TWO_STAGE) {
            engine.setOnThinkingBegin(this::onThinkingBegin);
            engine.onThinkingToken(this::onThinkingToken);
            engine.onReasoning(this::onReasoning);
        }

        Path historyPath = Path.of(System.getProperty("user.home"), ".clawkit", "history");
        try {
            reader = LineReaderBuilder.builder()
                .terminal(TerminalBuilder.terminal())
                .completer(new ClawkitCompleter())
                .variable(LineReader.HISTORY_FILE, historyPath)
                .build();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to initialize terminal", e);
        }

        // JLine3 跨平台 Ctrl+C 信号处理（替代 sun.misc.Signal）
        reader.getTerminal().handle(Terminal.Signal.INT, signal -> {
            engine.interrupt();
        });

        engine.setApprovalHandler(this::handleApproval);

        // SubAgent 回调 — 用户可见的派发/完成信息
        engine.onSubAgentSpawn(event -> {
            String inst = event.instruction();
            if (inst.length() > 100) inst = inst.substring(0, 100) + "...";
            System.out.println(GRAY + "  [SubAgent] dispatching: \"" + inst + "\" ("
                + event.type() + ", max " + event.maxTurns() + " turns)" + RESET);
        });
        engine.onSubAgentComplete(event -> {
            String summary = event.summary();
            if (summary.length() > 100) summary = summary.substring(0, 100) + "...";
            String line = summary.replace("\n", " ");
            System.out.println(GRAY + "  [SubAgent] done (" + event.turnsUsed()
                + " turns, ~" + event.tokens() + " tk, " + event.durationMs() + "ms) → "
                + line + RESET);
        });

        engine.onToolStart(event -> {
            String args = event.argSummary().isEmpty() ? "" : "  " + GRAY + event.argSummary() + RESET;
            System.out.println("  [..] " + event.name() + args);
        });

        engine.onToolEnd(event -> {
            String icon = event.success() ? "OK" : "ER";
            String detail = event.success() ? event.detail() : GRAY + event.detail() + RESET;
            System.out.println("  [" + icon + "] " + event.name() + "  " + detail);
        });

        engine.onStateChange(event -> {
            if (event.state() == AgentState.EXECUTING) {
                // 工具执行由 onToolStart/onToolEnd 显示，此处不再重复
            }
        });

        log.info("clawkit started — model={}, thinking={}", model, mode);

        printBanner(model, registry.count(), resolvedWorkDir.toString());

        while (true) {
            String input;
            try {
                input = reader.readLine("> ");
            } catch (UserInterruptException e) {
                // Ctrl+C while idle — clear line, continue
                System.out.println("\n  Press Enter or type /exit to quit.");
                continue;
            }
            if (input == null) break; // EOF (Ctrl+D)
            input = input.strip();
            if (input.isBlank()) continue;

            // === 记忆命令 ===
            if (input.equals("/remember") || input.startsWith("/remember ")) {
                handleRemember(reader, memoryService, provider, engine, input);
                continue;
            }
            if (input.startsWith("/memory")) {
                handleMemory(input, memoryService, engine);
                continue;
            }

            // === 斜杠命令 ===
            if (input.equals("/session")) {
                printSessionUsage();
                continue;
            }
            if (input.startsWith("/session ")) {
                handleSessionCommand(input.substring("/session ".length()).trim());
                continue;
            }
            if (input.startsWith("/mcp")) {
                handleMcpCommand(input.substring("/mcp".length()).trim());
                continue;
            }
            if (input.startsWith("/skill")) {
                handleSkillCommand(input.substring("/skill".length()).trim());
                continue;
            }
            if (input.startsWith("/")) {
                switch (input) {
                    case "/" -> printMenu(engine.thinkingMode(), engine.permissionMode());
                    case "/h", "/help" -> printHelp(engine.thinkingMode(), engine.permissionMode());
                    case "/q", "/exit" -> {
                        if (mcpManager != null) mcpManager.shutdown();
                        System.out.println("Goodbye.");
                        return;
                    }
                    case "/t", "/thinking" -> toggleThinking(engine);
                    case "/p", "/plan" -> setPermissionMode(engine, PermissionMode.PLAN);
                    case "/a", "/ask" -> setPermissionMode(engine, PermissionMode.ASK);
                    case "/auto" -> setPermissionMode(engine, PermissionMode.AUTO);
                    case "/plan-exec" -> togglePlanExec(engine);
                    case "/c", "/clear" -> {
                        engine.clearSession();
                        System.out.println(GRAY + "  session cleared." + RESET + "\n");
                        log.info("Session cleared");
                    }
                    case "/feishu-on" -> startImChannel("feishu", reader);
                    case "/feishu-off" -> stopImChannel("feishu");
                    case "/im-on" -> {
                        String[] parts = input.split("\\s+", 3);
                        if (parts.length >= 2) startImChannel(parts[1], reader);
                        else System.out.println(GRAY + "  Usage: /im-on feishu|weixin" + RESET + "\n");
                    }
                    case "/im-off" -> {
                        String[] parts = input.split("\\s+", 3);
                        if (parts.length >= 2) stopImChannel(parts[1]);
                        else stopAllImChannels();
                    }
                    case "/im-status" -> printImStatus();
                    case "/compact" -> {
                        String stats = engine.compactSession();
                        System.out.println(GRAY + "  " + stats + RESET + "\n");
                        log.info("Manual compaction: {}", stats);
                    }
                    case "/context" -> {
                        printContext(engine);
                    }
                    case "/runs" -> printRuns();
                    case "/metrics" -> printMetrics(input);
                    case "/trace" -> printTrace(input);
                    default -> System.out.println("未知命令，输入 / 查看菜单。\n");
                }
                continue;
            }

            if (!engine.tryAcquire()) {
                System.out.println(GRAY + "  Engine busy (IM is processing)...\n" + RESET);
                continue;
            }
            try {
                for (var ch : imChannels) {
                    if (ch.isRunning()) ch.mirrorToIm(input);
                }
                String result = engine.run(input);
                // PLAN 模式：LLM 生成的计划自动同步到 .clawkit/plan.md
                if (engine.permissionMode() == PermissionMode.PLAN
                    && result != null && !result.startsWith("[")) {
                    engine.writePlanToWorkspace(result);
                }
                for (var ch : imChannels) {
                    if (ch.isRunning()) ch.finalizeMirror(result);
                }
                if (result.startsWith("[")) {
                    System.out.println("\n" + result + "\n");
                } else {
                    System.out.println("\n");
                }
            } catch (Exception e) {
                log.error("Engine error: {}", e.getMessage(), e);
                System.err.println("[A-003] " + e.getMessage());
                for (var ch : imChannels) {
                    if (ch.isRunning()) ch.finalizeMirror(null);
                }
            } finally {
                engine.release();
            }
        }

        if (mcpManager != null) mcpManager.shutdown();
        log.info("clawkit exiting normally.");
        System.out.println("Goodbye.");
    }

    private ApprovalResult handleApproval(ApprovalRequest req) {
        printApprovalBox(req);

        for (int attempt = 0; attempt < 5; attempt++) {
            System.out.print(GRAY + "  [y]批准 [a]同类型全放行 [n]拒绝 [m]修改参数 > " + RESET);
            String input = reader.readLine("").trim().toLowerCase();

            return switch (input) {
                case "", "y" -> new ApprovalResult.Approve();
                case "a" -> new ApprovalResult.ApproveAllSameType(req.toolName());
                case "n" -> {
                    System.out.print(GRAY + "  拒绝原因（可选，回车跳过）> " + RESET);
                    String reason = reader.readLine("").trim();
                    yield new ApprovalResult.Reject(
                        reason.isEmpty() ? "User denied " + req.toolName() : reason);
                }
                case "m" -> {
                    System.out.print(GRAY + "  修改建议 > " + RESET);
                    String guidance = reader.readLine("").trim();
                    yield new ApprovalResult.ModifyParams(
                        guidance.isEmpty() ? "Please use different parameters." : guidance);
                }
                default -> {
                    System.out.println(GRAY + "  ?? 请输入 y/a/n/m" + RESET);
                    yield null;
                }
            };
        }
        return new ApprovalResult.Reject("连续多次无效输入");
    }

    private void printApprovalBox(ApprovalRequest req) {
        String riskIcon = switch (req.riskLevel()) {
            case HIGH   -> "!!";
            case MEDIUM -> "! ";
            case LOW    -> "  ";
        };
        String riskName = switch (req.riskLevel()) {
            case HIGH -> "高危"; case MEDIUM -> "中危"; case LOW -> "低危";
        };

        System.out.println();
        System.out.println("  +----------------------------------------+");
        System.out.println("  |  " + riskIcon + "  需要审批                          |");
        System.out.println("  +----------------------------------------+");
        System.out.println("  |  工具: " + padRight(req.toolName(), 31) + "|");
        System.out.println("  |  等级: " + padRight(riskIcon + " " + riskName, 31) + "|");
        System.out.println("  |  风险: " + padRight(req.riskDescription(), 31) + "|");
        System.out.println("  +----------------------------------------+");
        System.out.println("  |  参数:                                  |");
        for (String line : req.parameters().split("\n")) {
            if (line.length() > 38) line = line.substring(0, 38);
            System.out.println("  |    " + padRight(line, 38) + "|");
        }
        if (req.llmIntent() != null && !req.llmIntent().isEmpty()) {
            System.out.println("  |  ---                                   |");
            String intent = req.llmIntent();
            if (intent.length() > 38) intent = intent.substring(0, 38);
            System.out.println("  |  " + padRight(intent, 38) + "|");
        }
        System.out.println("  +----------------------------------------+");
        System.out.println();
    }

    private void printContext(AgentEngine engine) {
        int tokens = engine.getEstimatedTokens();
        int maxTokens = engine.getContextWindow();
        int pct = maxTokens > 0 ? tokens * 100 / maxTokens : 0;
        String bar = "░".repeat(10);
        int filled = Math.min(pct * 10 / 100, 10);
        bar = "█".repeat(filled) + "░".repeat(10 - filled);

        int msgCount = engine.getMessageCount();
        System.out.println();
        System.out.println(GRAY + "  context  [" + bar + "] " + pct + "%  ~" + tokens
            + " / " + maxTokens + " tokens  (" + msgCount + " msgs)" + RESET);

        // Phase 1: 预算报告（分区显示）
        var report = engine.getContextBudgetReport();
        if (report != null) {
            String statusIcon = switch (report.status()) {
                case OK -> GREEN + "OK" + RESET;
                case WARN -> YELLOW + "WARN" + RESET;
                case COMPACT_REQUIRED -> RED + "COMPACT" + RESET;
                case HARD_LIMIT -> RED + "HARD" + RESET;
            };
            System.out.println(GRAY + "  budget   " + statusIcon + GRAY
                + "  SYSTEM:" + report.sections().getOrDefault(com.clawkit.context.ContextSection.SYSTEM, 0)
                + "  TOOLS:" + report.sections().getOrDefault(com.clawkit.context.ContextSection.TOOLS, 0)
                + "  HISTORY:" + report.sections().getOrDefault(com.clawkit.context.ContextSection.HISTORY, 0)
                + "  MEMORY:" + report.sections().getOrDefault(com.clawkit.context.ContextSection.MEMORY, 0)
                + "  TOOL_RESULT:" + report.sections().getOrDefault(com.clawkit.context.ContextSection.TOOL_RESULT, 0)
                + RESET);
            if (report.suggestedAction() != null && !report.suggestedAction().isBlank()) {
                System.out.println(GRAY + "           " + report.suggestedAction() + RESET);
            }
        } else {
            // Fallback to old breakdown
            var bd = engine.getTokenBreakdown();
            if (!bd.isEmpty()) {
                System.out.println(GRAY + "  tokens   system:" + bd.getOrDefault("system", 0)
                    + "  user:" + bd.getOrDefault("user", 0)
                    + "  assistant:" + bd.getOrDefault("assistant", 0)
                    + "  tool:" + bd.getOrDefault("tool", 0) + RESET);
            }
        }

        var mask = engine.getLastMaskedContext();
        if (mask != null) {
            int evicted = mask.evictedTurnGroups() != null
                ? mask.evictedTurnGroups().size() : 0;
            System.out.println(GRAY + "  mask     T0:" + mask.tier0Count()
                + "  T1:" + mask.tier1Count()
                + "  T2:" + mask.tier2Count()
                + "  T3:" + mask.tier3Count()
                + (evicted > 0 ? "  (evicted:" + evicted + " groups)" : "") + RESET);
        }

        // 压缩状态
        String compStatus = engine.getCompactionStatus();
        if (compStatus != null && !compStatus.isEmpty()) {
            System.out.println(GRAY + "  compact  " + compStatus + RESET);
        }

        System.out.println(GRAY + "  mode     " + engine.thinkingMode() + " / " + engine.permissionMode() + RESET);
        System.out.println(GRAY + "  workdir  " + engine.workDir() + RESET);
        System.out.println();
    }

    // ── /runs /metrics /trace handlers ──────────────────────────────

    private void printRuns() {
        System.out.println();
        try {
            var runs = runReader.listRecent(10);
            if (runs.isEmpty()) {
                System.out.println(GRAY + "  暂无运行记录。执行一次任务后 run 记录会自动生成。" + RESET);
                System.out.println();
                return;
            }
            System.out.println(GRAY + "  run                                     status      turns  tools  fails  compact  time     mode" + RESET);
            System.out.println(GRAY + "  ──────────────────────────────────────── ─────────── ───── ────── ────── ──────── ──────── ──────" + RESET);
            for (var r : runs) {
                String time = r.startTime().length() > 16 ? r.startTime().substring(11, 16) : r.startTime();
                String duration = formatDuration(r.durationMs());
                System.out.printf("  %-40s %-11s %5d %6d %6d %8d %8s %s%n",
                    r.runId(), r.status(), r.turns(), r.toolCalls(),
                    r.toolFailures(), r.compactCount(), duration, r.permissionMode());
            }
            System.out.println();
        } catch (Exception e) {
            System.out.println(GRAY + "  读取 run 记录失败: " + e.getMessage() + RESET);
        }
    }

    private void printMetrics(String input) {
        System.out.println();
        try {
            String runId = extractRunId(input, "/metrics");
            com.clawkit.observability.model.RunMetrics m;
            if (runId != null) {
                m = runReader.readMetrics(runId);
            } else {
                var runs = runReader.listRecent(1);
                if (runs.isEmpty()) {
                    System.out.println(GRAY + "  暂无运行记录" + RESET);
                    System.out.println();
                    return;
                }
                m = runReader.readMetrics(runs.get(0).runId());
            }
            if (m == null) {
                System.out.println(GRAY + "  run 记录不存在: " + runId + RESET);
                System.out.println();
                return;
            }
            System.out.printf("  run:      %s%n", m.runId());
            System.out.printf("  status:   %s%n", m.status());
            System.out.printf("  duration: %s%n", formatDuration(m.durationMs()));
            System.out.printf("  turns:    %d%n", m.turns());
            System.out.printf("  tools:    %d (%d failed)%n", m.toolCalls(), m.toolFailures());
            System.out.printf("  compact:  %d%n", m.compactCount());
            System.out.printf("  mode:     %s / %s / %s%n", m.permissionMode(), m.thinkingMode(), m.executionMode());
            if (m.errorMessage() != null) {
                System.out.printf("  error:    %s%n", m.errorMessage());
            }
            System.out.println();
        } catch (Exception e) {
            System.out.println(GRAY + "  读取 metrics 失败: " + e.getMessage() + RESET);
        }
    }

    private void printTrace(String input) {
        System.out.println();
        try {
            String runId = extractRunId(input, "/trace");
            if (runId == null) {
                var runs = runReader.listRecent(1);
                if (runs.isEmpty()) {
                    System.out.println(GRAY + "  暂无运行记录" + RESET);
                    System.out.println();
                    return;
                }
                runId = runs.get(0).runId();
            }
            var lines = runReader.readTrace(runId);
            if (lines.isEmpty()) {
                System.out.println(GRAY + "  trace 为空: " + runId + RESET);
                System.out.println();
                return;
            }
            System.out.println(GRAY + "  trace: " + runId + " (" + lines.size() + " events)" + RESET);
            // 只显示关键事件摘要，不打印完整 JSON
            int shown = 0;
            for (String line : lines) {
                if (line.contains("RunStarted") || line.contains("RunCompleted")
                    || line.contains("ToolCompleted") || line.contains("CompactCompleted")) {
                    String shortLine = line.length() > 120 ? line.substring(0, 117) + "..." : line;
                    System.out.println(GRAY + "  " + shortLine + RESET);
                    shown++;
                }
                if (shown >= 20) {
                    System.out.println(GRAY + "  ... (" + (lines.size() - shown) + " more events)" + RESET);
                    break;
                }
            }
            System.out.println();
        } catch (Exception e) {
            System.out.println(GRAY + "  读取 trace 失败: " + e.getMessage() + RESET);
        }
    }

    private String extractRunId(String input, String command) {
        String rest = input.substring(command.length()).trim();
        return rest.isEmpty() ? null : rest;
    }

    private String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        long min = ms / 60_000;
        long sec = (ms % 60_000) / 1000;
        return min + "m" + sec + "s";
    }

    /** 解析斜杠命令输入，返回规范化的命令名；非斜杠命令返回 null */
    static String resolveCommand(String input) {
        if (input == null || input.isBlank()) return null;
        if (!input.startsWith("/")) return null;
        return switch (input) {
            case "/" -> "menu";
            case "/h", "/help" -> "help";
            case "/q", "/exit" -> "exit";
            case "/t", "/thinking" -> "thinking";
            case "/p", "/plan" -> "plan";
            case "/a", "/ask" -> "ask";
            case "/auto" -> "auto";
            case "/plan-exec" -> "plan-exec";
            case "/c", "/clear" -> "clear";
            case "/compact" -> "compact";
            case "/context" -> "context";
            case "/runs" -> "runs";
            case "/metrics" -> "metrics";
            case "/trace" -> "trace";
            case "/feishu-on" -> "feishu-on";
            case "/feishu-off" -> "feishu-off";
            case "/im-on" -> "im-on";
            case "/im-off" -> "im-off";
            case "/im-status" -> "im-status";
            case "/skill" -> "skill";
            case "/mcp" -> "mcp";
            default -> null;
        };
    }

    static String formatTokens(int tokens) {
        if (tokens < 1000) return tokens + "t";
        return String.format("%.1fkt", tokens / 1000.0);
    }

    // === 记忆命令 ===

    private static final String MEMORY_EXTRACT_PROMPT =
        "Extract metadata from this user memory.\n"
        + "Output ONLY a JSON object with keys: name, description, type. No other text.\n\n"
        + "name: kebab-case identifier (e.g. \"coding-style\", \"short-replies\")\n"
        + "description: one-line summary under 120 chars\n"
        + "type: one of [user, feedback, project, reference]\n"
        + "  user = personal preferences, coding style, role, knowledge\n"
        + "  feedback = corrections about how to approach tasks\n"
        + "  project = facts about ongoing work, goals, deadlines\n"
        + "  reference = pointers to external resources\n\n"
        + "Memory content:\n";

    private void handleRemember(LineReader reader, DiskMemoryService service,
                                LLMProvider provider, AgentEngine engine,
                                String input) {
        String rawContent = input.substring("/remember".length()).trim();

        // 无内容 → 提示输入
        if (rawContent.isEmpty()) {
            rawContent = reader.readLine(GRAY + "  What should I remember? " + RESET);
            if (rawContent == null || rawContent.isBlank()) {
                System.out.println(GRAY + "  Cancelled." + RESET + "\n");
                return;
            }
        }

        // 调用 LLM 提取元数据
        System.out.print(GRAY + "  extracting metadata..." + RESET);
        String json;
        try {
            Message msg = provider.generate(
                List.of(Message.system(MEMORY_EXTRACT_PROMPT), Message.user(rawContent)),
                List.of());
            json = msg.content() != null ? msg.content().trim() : "";
        } catch (Exception e) {
            log.warn("Memory metadata extraction failed: {}", e.getMessage());
            System.out.println(GRAY + " failed, using defaults." + RESET);
            json = "";
        }

        // 解析 LLM 输出
        String name = "";
        String description = "";
        MemoryType type = MemoryType.USER;
        if (!json.isEmpty()) {
            try {
                var node = new ObjectMapper().readTree(json);
                name = node.has("name") ? node.get("name").asText() : "";
                description = node.has("description") ? node.get("description").asText() : "";
                if (node.has("type")) {
                    type = parseMemoryType(node.get("type").asText());
                }
            } catch (Exception e) {
                log.warn("Failed to parse memory metadata JSON: {}", json);
            }
        }

        // 兜底
        if (name.isEmpty()) {
            name = rawContent.length() > 30
                ? rawContent.substring(0, 30).replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase()
                : rawContent.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
        }
        if (description.isEmpty()) {
            description = rawContent.length() > 100
                ? rawContent.substring(0, 100) + "..."
                : rawContent;
        }
        if (type == null) type = MemoryType.USER;

        MemoryEntry entry = new MemoryEntry(name, description, type, Instant.now(), rawContent);
        service.save(entry);
        engine.setMemoryIndex(service.loadIndex());
        System.out.println(GRAY + "\r  saved " + entry.filename()
            + " [" + type.name().toLowerCase() + "] " + description + RESET + "\n");
    }

    private void handleMemory(String input, DiskMemoryService service,
                              AgentEngine engine) {
        String sub = input.substring("/memory".length()).trim();
        if (sub.equals("list") || sub.isEmpty()) {
            var entries = service.listIndex();
            if (entries.isEmpty()) {
                System.out.println(GRAY + "  No memory entries." + RESET + "\n");
                return;
            }
            System.out.println();
            for (var entry : entries) {
                System.out.println(GRAY + "  - [" + entry.name() + "]("
                    + entry.filename() + ") — " + entry.description() + RESET);
            }
            System.out.println();
        } else if (sub.equals("regen")) {
            service.regenerateIndex();
            engine.setMemoryIndex(service.loadIndex());
            System.out.println(GRAY + "  Index regenerated from files." + RESET + "\n");
        } else if (sub.startsWith("add ")) {
            handleMemoryAdd(sub.substring("add ".length()).trim(), service, engine);
        } else {
            System.out.println(GRAY + "  Usage: /memory list | /memory add <type> <name> <content> | /memory regen" + RESET + "\n");
        }
    }

    private void handleMemoryAdd(String args, DiskMemoryService service,
                                 AgentEngine engine) {
        // 解析: <type> <name> <content>
        String[] parts = args.split("\\s+", 3);
        if (parts.length < 3) {
            System.out.println(GRAY + "  Usage: /memory add <type> <name> <content>" + RESET);
            System.out.println(GRAY + "  Types: user, feedback, project, reference" + RESET + "\n");
            return;
        }
        MemoryType type;
        try {
            type = MemoryType.valueOf(parts[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            System.out.println(GRAY + "  Invalid type: " + parts[0]
                + ". Use: user, feedback, project, reference" + RESET + "\n");
            return;
        }
        String name = parts[1].replaceAll("[^a-zA-Z0-9_-]", "_");
        String content = parts[2];
        String description = content.length() > 100
            ? content.substring(0, 100) + "..." : content;

        MemoryEntry entry = new MemoryEntry(name, description, type, Instant.now(), content);
        service.save(entry);
        engine.setMemoryIndex(service.loadIndex());
        System.out.println(GRAY + "  saved " + entry.filename()
            + " [" + type.name().toLowerCase() + "]" + RESET + "\n");
    }

    /** Load hierarchical CLAUDE.md: ~/.clawkit/CLAUDE.md + ./CLAUDE.md (fallback AGENTS.md). */
    static String loadWorkspaceRules(Path workDir) {
        Path homeDir = Path.of(System.getProperty("user.home"));
        String userRules = readIfExists(homeDir.resolve(".clawkit").resolve("CLAUDE.md"));
        String projectRules = readIfExists(workDir.resolve("CLAUDE.md"));
        if (projectRules.isEmpty()) {
            projectRules = readIfExists(workDir.resolve("AGENTS.md"));
        }
        if (userRules.isEmpty() && projectRules.isEmpty()) return "";
        if (userRules.isEmpty()) return projectRules;
        if (projectRules.isEmpty()) return userRules;
        return userRules + "\n\n" + projectRules;
    }

    private static String readIfExists(Path path) {
        if (Files.exists(path)) {
            try {
                return Files.readString(path);
            } catch (Exception e) {
                log.warn("Failed to read {}: {}", path, e.getMessage());
            }
        }
        return "";
    }

    static String readProjectContext(Path workDir) {
        Path claudeMd = workDir.resolve("CLAUDE.md");
        if (Files.exists(claudeMd)) {
            try {
                String content = Files.readString(claudeMd);
                log.info("Loaded CLAUDE.md from workDir ({} chars)", content.length());
                return content;
            } catch (Exception e) {
                log.warn("Failed to read CLAUDE.md: {}", e.getMessage());
            }
        }
        Path agentsMd = workDir.resolve("AGENTS.md");
        if (Files.exists(agentsMd)) {
            try {
                String content = Files.readString(agentsMd);
                log.info("Loaded AGENTS.md from workDir ({} chars)", content.length());
                return content;
            } catch (Exception e) {
                log.warn("Failed to read AGENTS.md: {}", e.getMessage());
            }
        }
        return "";
    }

    static String buildFullContext(String projectContext, String memoryIndex) {
        StringBuilder sb = new StringBuilder();
        if (projectContext != null && !projectContext.isBlank()) {
            sb.append("## Project Context\n\n").append(projectContext.stripTrailing());
        }
        if (memoryIndex != null && !memoryIndex.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(memoryIndex.stripTrailing());
        }
        return sb.toString();
    }

    // === 会话命令 ===

    private void handleSessionCommand(String args) {
        if (args.startsWith("save")) {
            String name = args.substring("save".length()).trim();
            if (name.isEmpty()) {
                name = "session-" + Instant.now().toString().replace(":", "-").substring(0, 19);
            }
            String result = engine.saveSession(name);
            System.out.println(GRAY + "  " + result + RESET + "\n");
        } else if (args.startsWith("load ")) {
            String id = args.substring("load ".length()).trim();
            try {
                engine.loadSession(id);
                System.out.println(GRAY + "  Session loaded: " + id + RESET + "\n");
            } catch (Exception e) {
                System.out.println(GRAY + "  [H-003] " + e.getMessage() + RESET + "\n");
            }
        } else if (args.equals("list")) {
            List<SessionMeta> sessions = engine.listSessions();
            if (sessions.isEmpty()) {
                System.out.println(GRAY + "  No saved sessions." + RESET + "\n");
                return;
            }
            System.out.println();
            for (SessionMeta s : sessions) {
                String updated = s.updatedAt().toString().replace("T", " ").substring(0, 16);
                String first = s.firstUserMessage() != null ? s.firstUserMessage() : "";
                if (first.length() > 60) first = first.substring(0, 60) + "...";
                System.out.println(GRAY + "  [" + s.id() + "] " + s.name()
                    + "  (" + s.messageCount() + " msgs, " + updated + ")" + RESET);
                if (!first.isEmpty()) {
                    System.out.println(GRAY + "      " + first + RESET);
                }
            }
            System.out.println();
        } else if (args.startsWith("delete ")) {
            String id = args.substring("delete ".length()).trim();
            try {
                engine.deleteSession(id);
                System.out.println(GRAY + "  Session deleted: " + id + RESET + "\n");
            } catch (Exception e) {
                System.out.println(GRAY + "  [H-003] " + e.getMessage() + RESET + "\n");
            }
        } else if (args.startsWith("search ")) {
            String query = args.substring("search ".length()).trim();
            if (sessionService == null) {
                System.out.println(GRAY + "  Session service not available." + RESET + "\n");
                return;
            }
            List<SessionMeta> matches = sessionService.search(query);
            if (matches.isEmpty()) {
                System.out.println(GRAY + "  No sessions matching \"" + query + "\"." + RESET + "\n");
                return;
            }
            System.out.println();
            for (SessionMeta s : matches) {
                String updated = s.updatedAt().toString().replace("T", " ").substring(0, 16);
                System.out.println(GRAY + "  [" + s.id() + "] " + s.name()
                    + "  (" + s.messageCount() + " msgs, " + updated + ")" + RESET);
                if (s.summary() != null && !s.summary().isBlank()) {
                    System.out.println(GRAY + "      " + s.summary() + RESET);
                }
            }
            System.out.println();
        } else if (args.equals("stats")) {
            if (sessionService == null) {
                System.out.println(GRAY + "  Session service not available." + RESET + "\n");
                return;
            }
            List<SessionService.AgeBucket> buckets = sessionService.stats();
            if (buckets.isEmpty()) {
                System.out.println(GRAY + "  No saved sessions." + RESET + "\n");
                return;
            }
            long totalBytes = 0;
            int totalCount = 0;
            System.out.println();
            System.out.println(GRAY + "  Session storage breakdown:" + RESET);
            for (SessionService.AgeBucket b : buckets) {
                System.out.println(GRAY + "    " + padRight(b.label(), 16)
                    + String.format("%3d sessions", b.count())
                    + String.format("%8s", formatSize(b.bytes())) + RESET);
                totalBytes += b.bytes();
                totalCount += b.count();
            }
            System.out.println(GRAY + "    " + padRight("───", 16)
                + "───────────" + "  ────────" + RESET);
            System.out.println(GRAY + "    " + padRight("Total", 16)
                + String.format("%3d sessions", totalCount)
                + String.format("%8s", formatSize(totalBytes)) + RESET);
            System.out.println();
        } else if (args.startsWith("prune")) {
            String rest = args.substring("prune".length()).trim();
            int days;
            try {
                days = Integer.parseInt(rest);
                if (days <= 0) {
                    System.out.println(GRAY + "  Usage: /session prune <days>  (days must be > 0)" + RESET + "\n");
                    return;
                }
            } catch (NumberFormatException e) {
                System.out.println(GRAY + "  Usage: /session prune <days>  (e.g. /session prune 30)" + RESET + "\n");
                return;
            }
            if (sessionService == null) {
                System.out.println(GRAY + "  Session service not available." + RESET + "\n");
                return;
            }
            int count = sessionService.prune(days);
            if (count == 0) {
                System.out.println(GRAY + "  No sessions older than " + days + " days." + RESET + "\n");
            } else {
                System.out.println(GRAY + "  Pruned " + count + " session(s) older than " + days + " days." + RESET + "\n");
            }
        } else if (args.equals("new")) {
            String result = engine.newSession();
            System.out.println(GRAY + "  " + result + RESET + "\n");
        } else {
            printSessionUsage();
        }
    }

    private void printSessionUsage() {
        System.out.println();
        System.out.println(GRAY + "  /session save [name]     save current session" + RESET);
        System.out.println(GRAY + "  /session load <id>       load a saved session" + RESET);
        System.out.println(GRAY + "  /session list            list all saved sessions" + RESET);
        System.out.println(GRAY + "  /session search <query>  search past sessions" + RESET);
        System.out.println(GRAY + "  /session stats           show storage breakdown by age" + RESET);
        System.out.println(GRAY + "  /session prune <days>    delete sessions older than N days" + RESET);
        System.out.println(GRAY + "  /session delete <id>     delete a specific session" + RESET);
        System.out.println(GRAY + "  /session new             clear and start new session" + RESET);
        System.out.println();
    }

    // === MCP 命令 ===

    private void handleMcpCommand(String args) {
        if (mcpManager == null) {
            System.out.println(GRAY + "  MCP manager not initialized." + RESET + "\n");
            return;
        }

        if (args.isEmpty()) {
            var statuses = mcpManager.status();
            if (statuses.isEmpty()) {
                System.out.println(GRAY + "  No MCP servers configured." + RESET + "\n");
                return;
            }
            System.out.println();
            System.out.println(GRAY + "  MCP Servers:" + RESET);
            for (var s : statuses) {
                String icon = "RUNNING".equals(s.state()) ? "●" : "○";
                String tools = s.toolCount() > 0 ? s.toolCount() + " tools" : "—";
                System.out.println(GRAY + "    " + icon + " " + padRight(s.name(), 20)
                    + padRight(s.transport(), 8) + padRight(s.state(), 10) + tools + RESET);
            }
            System.out.println();
            return;
        }

        if (args.startsWith("restart ")) {
            String name = args.substring("restart ".length()).trim();
            List<Tool> tools = mcpManager.restart(name);
            for (Tool t : tools) registry.register(t);
            System.out.println(GRAY + "  MCP server '" + name + "' restarted with "
                + tools.size() + " tools." + RESET + "\n");
            return;
        }

        if (args.startsWith("logs ")) {
            String name = args.substring("logs ".length()).trim();
            List<String> logs = mcpManager.logs(name);
            if (logs.isEmpty()) {
                System.out.println(GRAY + "  No logs for: " + name + RESET + "\n");
                return;
            }
            System.out.println();
            for (String line : logs) {
                System.out.println(GRAY + "  " + line + RESET);
            }
            System.out.println();
            return;
        }

        if (args.startsWith("disable ")) {
            String name = args.substring("disable ".length()).trim();
            mcpManager.disable(name);
            System.out.println(GRAY + "  MCP server '" + name + "' disabled." + RESET + "\n");
            return;
        }

        if (args.startsWith("enable ")) {
            String name = args.substring("enable ".length()).trim();
            List<Tool> tools = mcpManager.restart(name);
            for (Tool t : tools) registry.register(t);
            System.out.println(GRAY + "  MCP server '" + name + "' enabled with "
                + tools.size() + " tools." + RESET + "\n");
            return;
        }

        printMcpUsage();
    }

    private void printMcpUsage() {
        System.out.println();
        System.out.println(GRAY + "  /mcp                    list all MCP servers" + RESET);
        System.out.println(GRAY + "  /mcp restart <name>     restart a server" + RESET);
        System.out.println(GRAY + "  /mcp logs <name>        show server stderr logs" + RESET);
        System.out.println(GRAY + "  /mcp disable <name>     stop and disable a server" + RESET);
        System.out.println(GRAY + "  /mcp enable <name>      re-enable a disabled server" + RESET);
        System.out.println();
    }

    // === 技能命令 ===

    private void handleSkillCommand(String args) {
        if (args.isEmpty()) {
            printSkillUsage();
            return;
        }
        if (args.equals("list")) {
            var skills = skillLoader.listAll();
            if (skills.isEmpty()) {
                System.out.println(GRAY + "  No skills found." + RESET + "\n");
                return;
            }
            System.out.println();
            for (var s : skills) {
                String status = engine.hasSkillLoaded(s.name()) ? " (*loaded)" : "";
                System.out.println(GRAY + "  - " + s.name() + status + RESET);
                System.out.println(GRAY + "    " + s.description() + RESET);
            }
            System.out.println();
        } else if (args.startsWith("load ")) {
            String name = args.substring("load ".length()).trim();
            if (name.isEmpty()) {
                System.out.println(GRAY + "  Usage: /skill load <name>" + RESET + "\n");
                return;
            }
            String prompt = skillLoader.loadPrompt(name);
            if (prompt == null) {
                System.out.println(GRAY + "  [S-001] Skill not found: " + name + RESET + "\n");
                return;
            }
            engine.loadSkill(name, prompt);
            System.out.println(GRAY + "  Skill loaded: " + name + " (" + prompt.length() + " chars)" + RESET + "\n");
        } else if (args.startsWith("unload ")) {
            String name = args.substring("unload ".length()).trim();
            if (name.isEmpty()) {
                System.out.println(GRAY + "  Usage: /skill unload <name>" + RESET + "\n");
                return;
            }
            engine.unloadSkill(name);
            System.out.println(GRAY + "  Skill unloaded: " + name + RESET + "\n");
        } else {
            printSkillUsage();
        }
    }

    private void printSkillUsage() {
        System.out.println();
        System.out.println(GRAY + "  /skill list             list available skills" + RESET);
        System.out.println(GRAY + "  /skill load <name>      load a skill into current session" + RESET);
        System.out.println(GRAY + "  /skill unload <name>    unload a skill" + RESET);
        System.out.println();
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static MemoryType parseMemoryType(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return MemoryType.valueOf(s.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return switch (s.toLowerCase().trim()) {
                case "u" -> MemoryType.USER;
                case "f" -> MemoryType.FEEDBACK;
                case "p" -> MemoryType.PROJECT;
                case "r" -> MemoryType.REFERENCE;
                default -> null;
            };
        }
    }

    // === 命令处理 ===

    private void printMenu(ThinkingMode thinking, PermissionMode perm) {
        System.out.println();
        System.out.println(GRAY + "  /thinking   toggle thinking     /plan-exec plan+execute" + RESET);
        System.out.println(GRAY + "  /plan       read-only mode      (current: " + perm + ")" + RESET);
        System.out.println(GRAY + "  /ask        confirm writes      /auto   full-auto" + RESET);
        System.out.println(GRAY + "  /clear      reset session       /compact compress" + RESET);
        System.out.println(GRAY + "  /context    token usage         /remember add memory" + RESET);
        System.out.println(GRAY + "  /runs       recent runs         /metrics run summary" + RESET);
        System.out.println(GRAY + "  /trace      run event trace     /memory  list memories" + RESET);
        System.out.println(GRAY + "  /session    manage sessions     /skill load/unload" + RESET);
        System.out.println(GRAY + "  /mcp        MCP servers         /im-on /im-off /im-status" + RESET);
        System.out.println(GRAY + "  /help       show all commands   /exit  quit" + RESET);
        System.out.println();
    }

    private void printHelp(ThinkingMode thinking, PermissionMode perm) {
        System.out.println();
        System.out.println("  clawkit v0.1.0 — your local AI coding companion");
        System.out.println();
        System.out.println("  COMMANDS");
        System.out.println("    /thinking    toggle slow thinking mode  (current: " + thinking + ")");
        System.out.println("    /plan        read-only tools only       (current: " + perm + ")");
        System.out.println("    /ask         confirm before each write tool");
        System.out.println("    /auto        execute all tools without confirmation");
        System.out.println("    /clear       reset conversation session");
        System.out.println("    /compact     manually compress context (L1/L2)");
        System.out.println("    /context     show token usage and session info");
        System.out.println("    /runs        list recent runs");
        System.out.println("    /metrics     show run summary [runId]");
        System.out.println("    /trace       show trace events <runId>");
        System.out.println("    /remember    add a memory entry (interactive)");
        System.out.println("    /memory      list or manage memory entries");
        System.out.println("    /session     save, load, list, search sessions");
        System.out.println("    /skill       list, load, unload skills");
        System.out.println("    /mcp         manage MCP servers (status/restart/logs/disable/enable)");
        System.out.println("    /im-on <id>  enable IM channel (feishu / weixin)");
        System.out.println("    /im-off [id] disable a channel or all");
        System.out.println("    /im-status   show all IM channel status");
        System.out.println("    /feishu-on   (alias for /im-on feishu)");
        System.out.println("    /feishu-off  (alias for /im-off feishu)");
        System.out.println("    /help        show this help");
        System.out.println("    /exit        quit clawkit");
        System.out.println();
        System.out.println("  PERMISSION MODES");
        System.out.println("    AUTO   all tools execute automatically");
        System.out.println("    ASK    write tools prompt [y/n] before execution");
        System.out.println("    PLAN   read-only tools only; LLM must plan first");
        System.out.println();
        System.out.println("  KEYBINDINGS");
        System.out.println("    Ctrl+C   interrupt current operation");
        System.out.println("    Up/Down  navigate command history");
        System.out.println("    Tab      autocomplete paths and commands");
        System.out.println();
    }

    private void toggleThinking(AgentEngine engine) {
        ThinkingMode current = engine.thinkingMode();
        ThinkingMode next = current == ThinkingMode.TWO_STAGE
            ? ThinkingMode.OFF : ThinkingMode.TWO_STAGE;
        engine.setThinkingMode(next);
        engine.setOnThinkingBegin(next == ThinkingMode.TWO_STAGE
            ? this::onThinkingBegin : null);
        engine.onThinkingToken(next == ThinkingMode.TWO_STAGE
            ? this::onThinkingToken : null);
        engine.onReasoning(next == ThinkingMode.TWO_STAGE
            ? this::onReasoning : null);
        System.out.println(GRAY + "  thinking: " + current + " -> " + next + RESET + "\n");
        log.info("Thinking mode toggled: {} -> {}", current, next);
    }

    private void setPermissionMode(AgentEngine engine, PermissionMode mode) {
        PermissionMode old = engine.permissionMode();
        engine.setPermissionMode(mode);
        System.out.println(GRAY + "  permission: " + old + " -> " + mode + RESET + "\n");
        log.info("Permission mode switched: {} -> {}", old, mode);
    }

    private void togglePlanExec(AgentEngine engine) {
        boolean isPlanExec = engine.executionMode() == ExecutionMode.PLAN_EXECUTE;
        if (isPlanExec) {
            engine.setExecutionMode(ExecutionMode.REACT);
            System.out.println(GRAY + "  execution mode: PLAN_EXECUTE -> REACT" + RESET + "\n");
        } else {
            engine.setExecutionMode(ExecutionMode.PLAN_EXECUTE);
            engine.setOnPlanReady(this::confirmPlan);
            System.out.println(GRAY + "  execution mode: REACT -> PLAN_EXECUTE" + RESET + "\n");
        }
    }

    private boolean confirmPlan(ExecutionPlan plan) {
        System.out.println();
        System.out.println(GRAY + "  ╔══════════════════════════════════════╗" + RESET);
        System.out.println(GRAY + "  ║        Execution Plan                ║" + RESET);
        System.out.println(GRAY + "  ╚══════════════════════════════════════╝" + RESET);
        System.out.println("  Goal: " + plan.getGoal());
        System.out.println();

        List<List<String>> levels = PlanParser.computeLevels(plan.getTasks());

        for (int i = 0; i < levels.size(); i++) {
            System.out.println(GRAY + "  Level " + i + RESET);
            for (String taskId : levels.get(i)) {
                Task task = plan.getTask(taskId);
                if (task == null) continue;
                String emoji = switch (task.getTaskType()) {
                    case EXPLORE -> "🔍"; // 🔍
                    case MODIFY  -> "🔧"; // 🔧
                    case VERIFY  -> "✅";       // ✅
                };
                System.out.println("    " + emoji + " " + taskId + " [" + task.getTaskType() + "]");
                System.out.println("      " + task.getDescription());
                if (!task.getDependencies().isEmpty()) {
                    System.out.println(GRAY + "      depends on: " + String.join(", ", task.getDependencies()) + RESET);
                }
            }
            System.out.println();
        }

        System.out.print(GRAY + "  Execute this plan? [Y/n] " + RESET);
        try {
            String input = System.console().readLine().trim().toLowerCase();
            return input.isEmpty() || "y".equals(input) || "yes".equals(input);
        } catch (Exception e) {
            return false;
        }
    }

    // === 回调 ===

    private static final String GRAY = "\033[90m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String RESET = "\033[0m";

    private static final String THINKING_BAR = "─".repeat(54);

    private void onThinkingBegin() {
        System.out.print("\n");
        System.out.println(GRAY + "┌─ thinking " + THINKING_BAR);
        System.out.print("│ ");
    }

    /** Phase 1 流式输出：逐字打印，检测换行自动插入 │ 边框 */
    private void onThinkingToken(String token) {
        System.out.print(token);
        if (token.indexOf('\n') >= 0) {
            System.out.print("│ ");
        }
    }

    private void onReasoning(String text) {
        System.out.print("\n" + GRAY + "└" + THINKING_BAR + RESET + "\n");
    }

    private void printBanner(String model, int toolCount, String workDir) {
        // 内框宽度 62，总行宽 66 (2 margin + ║ + 62 + ║)
        final int W = 62;
        System.out.println();
        System.out.println(boxLine(W, "═".repeat(W), '╔', '╗'));
        System.out.println(boxLine(W, ""));
        System.out.println(boxLine(W, center("_       _      _", W)));
        System.out.println(boxLine(W, center("_ __ ___ (_)_ __ (_) ___| | __ ___      __", W)));
        System.out.println(boxLine(W, center("| '_ ` _ \\| | '_ \\| |/ __| |/ _` \\ \\ /\\ / /", W)));
        System.out.println(boxLine(W, center("| | | | | | | | | | | (__| | (_| |\\ V  V /", W)));
        System.out.println(boxLine(W, center("|_| |_| |_|_|_| |_|_|\\___|_|\\__,_| \\_/\\_/", W)));
        System.out.println(boxLine(W, ""));
        System.out.println(boxLine(W, center("your local AI coding companion", W)));
        System.out.println(boxLine(W, center("v0.1.0", W)));
        System.out.println(boxLine(W, ""));
        System.out.println(boxLine(W, "", '╠', '╣'));
        System.out.println(boxLine(W, "  model     " + padRight(model, W - 12)));
        System.out.println(boxLine(W, "  workdir   " + padRight(truncatePath(workDir, W - 12), W - 12)));
        int builtinCount = 8;
        int mcpCount = toolCount - builtinCount;
        String toolsLine = toolCount + " loaded (8 built-in";
        if (mcpCount > 0) toolsLine += " + " + mcpCount + " MCP";
        toolsLine += ")";
        System.out.println(boxLine(W, "  tools     " + padRight(truncatePath(toolsLine, W - 12), W - 12)));
        System.out.println(boxLine(W, "", '╠', '╣'));
        System.out.println(boxLine(W, "  /help     show all commands"));
        System.out.println(boxLine(W, "  /plan     read-only mode   /ask     confirm writes"));
        System.out.println(boxLine(W, "  /auto     full-auto        /clear   reset session"));
        System.out.println(boxLine(W, "  /thinking toggle thinking  /compact compress context"));
        System.out.println(boxLine(W, "  /context  show session     /session save/load"));
        System.out.println(boxLine(W, "  /memory   list memories    /skill  manage skills"));
        System.out.println(boxLine(W, "  /mcp      MCP servers      /im-on  /im-off  /im-status"));
        System.out.println(boxLine(W, "  /exit     quit             /help   all commands"));
        System.out.println(boxLine(W, "═".repeat(W), '╚', '╝'));
        System.out.println();
        System.out.println("  Try \"explain this project\" or \"fix the NPE in UserService.java\"");
        System.out.println();
    }

    static String boxLine(int width, String content) {
        return boxLine(width, content, '║', '║');
    }

    static String boxLine(int width, String content, char left, char right) {
        if (content.length() > width) content = content.substring(0, width);
        else content = padRight(content, width);
        return "  " + left + content + right;
    }

    static String center(String s, int width) {
        if (s.length() >= width) return s;
        int pad = (width - s.length()) / 2;
        return " ".repeat(pad) + s;
    }

    static String padRight(String s, int n) {
        if (s.length() >= n) return s;
        return s + " ".repeat(n - s.length());
    }

    static String truncatePath(String path, int maxLen) {
        if (path.length() <= maxLen) return path;
        return "..." + path.substring(path.length() - maxLen + 3);
    }

    private static Path resolveWorkDir(Path rootDir) {
        if (rootDir != null) {
            Path path = rootDir.toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                System.err.println("[C-003] --root path does not exist: " + path);
                System.exit(1);
            }
            if (!Files.isDirectory(path)) {
                System.err.println("[C-003] --root path is not a directory: " + path);
                System.exit(1);
            }
            return path;
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private String readConfigValue(String envKey, String altEnvKey, String... configPath) {
        String val = System.getenv(envKey);
        if (val != null && !val.isBlank()) return val;
        if (altEnvKey != null) {
            val = System.getenv(altEnvKey);
            if (val != null && !val.isBlank()) return val;
        }
        return ConfigHelper.readConfig(configPath);
    }

    private void startImBot(String channelName) {
        String apiKey = readConfigValue("CLAWKIT_API_KEY", "ANTHROPIC_AUTH_TOKEN", "apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[C-003] CLAWKIT_API_KEY not set (env or ~/.clawkit/config.yaml).");
            System.exit(1);
        }

        LLMConfig.Protocol proto;
        try {
            proto = LLMConfig.Protocol.valueOf(protocol.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("[C-004] Unknown protocol '" + protocol
                + "'. Valid: OPENAI_COMPAT, ANTHROPIC");
            System.exit(1);
            return;
        }

        LLMConfig llmConfig = LLMConfig.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .model(model)
            .protocol(proto)
            .build();

        Path workDir = resolvedWorkDir != null ? resolvedWorkDir : Path.of(System.getProperty("user.dir"));
        LLMProvider provider = ProviderFactory.create(llmConfig);
        Path skillsDir = Path.of(System.getProperty("user.home"), ".agents", "skills");
        ToolRegistry registry = createToolRegistry(workDir, skillsDir);
        AgentEngine eng = new AgentEngine(provider, registry, workDir.toString(),
            ThinkingMode.OFF, null, null, null);
        eng.setPermissionMode(PermissionMode.AUTO);
        eng.setWorkspaceRules(loadWorkspaceRules(workDir));

        ImChannel channel = switch (channelName) {
            case "feishu" -> new FeishuChannel(FeishuConfig.fromEnv());
            case "weixin" -> new WeixinChannel(WeixinConfig.fromEnv());
            default -> throw new IllegalArgumentException("Unknown channel: " + channelName);
        };
        channel.setEngine(eng);
        try {
            channel.start();
            channel.awaitShutdown();
        } catch (java.io.IOException | InterruptedException e) {
            log.error("Failed to start {} bot: {}", channelName, e.getMessage(), e);
            System.err.println("[C-003] Failed to start " + channelName + " bot: " + e.getMessage());
            System.exit(1);
        }
    }

    private void startImChannel(String name, LineReader reader) {
        ImChannel existing = imChannels.stream()
            .filter(c -> c.id().equals(name) && c.isRunning())
            .findFirst().orElse(null);
        if (existing != null) {
            System.out.println(GRAY + "  " + existing.name() + " is already running." + RESET + "\n");
            return;
        }

        try {
            ImChannel channel = switch (name) {
                case "feishu" -> {
                    var fc = new FeishuChannel(FeishuConfig.fromEnv());
                    fc.setOnImInput(text -> {
                        System.out.print("\r\033[K" + GRAY + "  [飞书] " + text + RESET + "\n> ");
                        System.out.flush();
                    });
                    yield fc;
                }
                case "weixin" -> {
                    var wc = new WeixinChannel(WeixinConfig.fromEnv());
                    wc.setOnImInput(text -> {
                        System.out.print("\r\033[K" + GRAY + "  [微信] " + text + RESET + "\n> ");
                        System.out.flush();
                    });
                    yield wc;
                }
                default -> throw new IllegalArgumentException("Unknown channel: " + name);
            };
            channel.setEngine(engine);
            channel.start();
            imChannels.add(channel);
            System.out.println(GRAY + "  " + channel.name() + " channel started." + RESET + "\n");
        } catch (Exception e) {
            log.error("Failed to start {} channel: {}", name, e.getMessage(), e);
            System.out.println(GRAY + "  [C-003] Failed: " + e.getMessage() + RESET + "\n");
        }
    }

    private void stopImChannel(String name) {
        ImChannel channel = imChannels.stream()
            .filter(c -> c.id().equals(name) && c.isRunning())
            .findFirst().orElse(null);
        if (channel == null) {
            System.out.println(GRAY + "  " + name + " is not running." + RESET + "\n");
            return;
        }
        channel.stop();
        imChannels.remove(channel);
        System.out.println(GRAY + "  " + channel.name() + " channel stopped." + RESET + "\n");
    }

    private void stopAllImChannels() {
        if (imChannels.isEmpty()) {
            System.out.println(GRAY + "  No IM channels active." + RESET + "\n");
            return;
        }
        for (var ch : imChannels) {
            if (ch.isRunning()) ch.stop();
        }
        imChannels.clear();
        System.out.println(GRAY + "  All IM channels stopped." + RESET + "\n");
    }

    private void printImStatus() {
        if (imChannels.isEmpty()) {
            System.out.println(GRAY + "  No IM channels active." + RESET + "\n");
            return;
        }
        System.out.println();
        for (var ch : imChannels) {
            var s = ch.status();
            String icon = s.running() ? "*" : " ";
            String user = s.linkedUser() != null ? s.linkedUser() : "—";
            System.out.println(GRAY + "  [" + icon + "] " + padRight(s.name(), 8)
                + "  user: " + user + "  " + s.stateInfo() + RESET);
        }
        System.out.println();
    }

    private ToolRegistry createToolRegistry(Path workDir, Path... extraReadRoots) {
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

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ClawkitApp()).execute(args);
        System.exit(exitCode);
    }
}
