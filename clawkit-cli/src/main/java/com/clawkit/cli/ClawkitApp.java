package com.clawkit.cli;

import com.clawkit.context.SkillLoader;
import com.clawkit.engine.ExecutionMode;
import com.clawkit.engine.PermissionMode;
import com.clawkit.engine.SessionService;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.engine.impl.PlanParser;
import com.clawkit.tools.schema.ExecutionPlan;
import com.clawkit.tools.schema.Task;
import com.clawkit.im.ImChannel;
import com.clawkit.im.feishu.FeishuChannel;
import com.clawkit.im.feishu.FeishuConfig;
import com.clawkit.im.weixin.WeixinChannel;
import com.clawkit.im.weixin.WeixinConfig;
import com.clawkit.memory.MemoryType;
import com.clawkit.memory.impl.DiskMemoryService;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.mcp.McpManager;
import java.nio.file.Files;
import java.nio.file.Path;
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
    versionProvider = BuildInfo.class
)
public class ClawkitApp implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClawkitApp.class);

    @Option(names = {"-m", "--model"},
        description = "Model name (default: deepseek-chat)")
    private String model;

    @Option(names = {"--base-url"},
        description = "API base URL")
    private String baseUrl;

    @Option(names = {"--protocol"},
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
    private LineReader reader;
    private com.clawkit.observability.RunReader runReader;
    private final ConsoleRenderer renderer = new ConsoleRenderer();
    private DiskMemoryService memoryService;
    private CliCommandHandlers commandHandlers;
    private EffectiveConfig effectiveConfig;

    @Override
    public void run() {
        if (imMode != null && !imMode.isBlank()) {
            startImBot(imMode.strip().toLowerCase());
            return;
        }

        // P0-R: 统一走 ApplicationBootstrap 装配
        var ctx = ApplicationBootstrap.bootstrap(model, baseUrl, protocol, thinking, rootDir);
        this.engine = ctx.engine();
        this.registry = ctx.registry();
        this.sessionService = ctx.sessionService();
        this.skillLoader = ctx.skillLoader();
        this.mcpManager = ctx.mcpManager();
        this.memoryService = ctx.memoryService();
        this.runReader = ctx.runReader();
        this.resolvedWorkDir = ctx.workDir();
        this.effectiveConfig = ctx.effectiveConfig();
        ThinkingMode mode = ctx.thinkingMode();

        // 加载工作区 rules（Bootstrap 已设置基础 rules，此处补充 CLI 特定逻辑）
        String workspaceRules = loadWorkspaceRules(resolvedWorkDir);
        engine.setWorkspaceRules(workspaceRules);

        if (mode == ThinkingMode.TWO_STAGE) {
            engine.setOnThinkingBegin(this::onThinkingBegin);
            engine.onThinkingToken(this::onThinkingToken);
            engine.onReasoning(this::onReasoning);
        }

        Path historyPath = Path.of(System.getProperty("user.home"), ".clawkit", "history");
        try {
            Terminal terminal = TerminalBuilder.terminal();
            if (System.console() == null && "dumb".equalsIgnoreCase(terminal.getType())) {
                terminal.close();
                throw new ConfigurationException("C-007", "Interactive terminal is required",
                    "the interactive CLI was not started",
                    "Run in Windows Terminal or use docker run -it.");
            }
            reader = LineReaderBuilder.builder()
                .terminal(terminal)
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

        engine.setApprovalHandler(new ApprovalConsole(() -> {
            try { return reader.readLine(""); } catch (Exception e) { return null; }
        }).asHandler());
        this.commandHandlers = new CliCommandHandlers(engine, sessionService, skillLoader,
            mcpManager, registry, memoryService, runReader, reader);

        // SubAgent 回调 — 委托给 ConsoleRenderer
        engine.onSubAgentSpawn(event -> renderer.onSubAgentSpawn(
            event.instruction(), event.type(), event.maxTurns()));
        engine.onSubAgentComplete(event -> renderer.onSubAgentComplete(
            event.summary(), event.turnsUsed(), event.tokens(), event.durationMs()));

        engine.onToolStart(event -> renderer.onToolStart(event.name(), event.argSummary()));
        engine.onToolEnd(event -> renderer.onToolEnd(event.name(), event.success(), event.detail()));
        engine.onStateChange(event -> { /* 工具执行由 onToolStart/onToolEnd 显示 */ });

        log.info("clawkit started — model={}, thinking={}", effectiveConfig.model(), mode);

        printBanner(effectiveConfig.model(), registry.count(), resolvedWorkDir.toString());

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

            // 斜杠命令分派 → 提取为独立方法（可独立测试）
            if (input.startsWith("/")) {
                if (dispatchCommand(input)) return;
                continue;
            }

            if (!engine.tryAcquire()) {
                System.out.println(ConsoleRenderer.GRAY + "  Engine busy (IM is processing)...\n" + ConsoleRenderer.RESET);
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
                String diagnosticId = CliErrorRenderer.renderUnexpected(e);
                log.error("[{}] Engine error: {}", diagnosticId, e.getMessage(), e);
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

    private void printContext(AgentEngine engine) {
        int tokens = engine.getEstimatedTokens();
        int maxTokens = engine.getContextWindow();
        int pct = maxTokens > 0 ? tokens * 100 / maxTokens : 0;
        String bar = "░".repeat(10);
        int filled = Math.min(pct * 10 / 100, 10);
        bar = "█".repeat(filled) + "░".repeat(10 - filled);

        int msgCount = engine.getMessageCount();
        System.out.println();
        System.out.println(ConsoleRenderer.GRAY + "  context  [" + bar + "] " + pct + "%  ~" + tokens
            + " / " + maxTokens + " tokens  (" + msgCount + " msgs)" + ConsoleRenderer.RESET);

        // Phase 1: 预算报告（分区显示）
        var report = engine.getContextBudgetReport();
        if (report != null) {
            String statusIcon = switch (report.status()) {
                case OK -> GREEN + "OK" + ConsoleRenderer.RESET;
                case WARN -> YELLOW + "WARN" + ConsoleRenderer.RESET;
                case COMPACT_REQUIRED -> RED + "COMPACT" + ConsoleRenderer.RESET;
                case HARD_LIMIT -> RED + "HARD" + ConsoleRenderer.RESET;
            };
            System.out.println(ConsoleRenderer.GRAY + "  budget   " + statusIcon + GRAY
                + "  SYSTEM:" + report.sections().getOrDefault(com.clawkit.context.ContextSection.SYSTEM, 0)
                + "  TOOLS:" + report.sections().getOrDefault(com.clawkit.context.ContextSection.TOOLS, 0)
                + "  HISTORY:" + report.sections().getOrDefault(com.clawkit.context.ContextSection.HISTORY, 0)
                + "  MEMORY:" + report.sections().getOrDefault(com.clawkit.context.ContextSection.MEMORY, 0)
                + "  TOOL_RESULT:" + report.sections().getOrDefault(com.clawkit.context.ContextSection.TOOL_RESULT, 0)
                + ConsoleRenderer.RESET);
            if (report.suggestedAction() != null && !report.suggestedAction().isBlank()) {
                System.out.println(ConsoleRenderer.GRAY + "           " + report.suggestedAction() + ConsoleRenderer.RESET);
            }
        } else {
            // Fallback to old breakdown
            var bd = engine.getTokenBreakdown();
            if (!bd.isEmpty()) {
                System.out.println(ConsoleRenderer.GRAY + "  tokens   system:" + bd.getOrDefault("system", 0)
                    + "  user:" + bd.getOrDefault("user", 0)
                    + "  assistant:" + bd.getOrDefault("assistant", 0)
                    + "  tool:" + bd.getOrDefault("tool", 0) + ConsoleRenderer.RESET);
            }
        }

        var mask = engine.getLastMaskedContext();
        if (mask != null) {
            int evicted = mask.evictedTurnGroups() != null
                ? mask.evictedTurnGroups().size() : 0;
            System.out.println(ConsoleRenderer.GRAY + "  mask     T0:" + mask.tier0Count()
                + "  T1:" + mask.tier1Count()
                + "  T2:" + mask.tier2Count()
                + "  T3:" + mask.tier3Count()
                + (evicted > 0 ? "  (evicted:" + evicted + " groups)" : "") + ConsoleRenderer.RESET);
        }

        // 压缩状态
        String compStatus = engine.getCompactionStatus();
        if (compStatus != null && !compStatus.isEmpty()) {
            System.out.println(ConsoleRenderer.GRAY + "  compact  " + compStatus + ConsoleRenderer.RESET);
        }

        System.out.println(ConsoleRenderer.GRAY + "  mode     " + engine.thinkingMode() + " / " + engine.permissionMode() + ConsoleRenderer.RESET);
        System.out.println(ConsoleRenderer.GRAY + "  workdir  " + engine.workDir() + ConsoleRenderer.RESET);
        System.out.println();
    }

    /** 解析斜杠命令输入 — 委托给 SlashCommandRouter */
    static String resolveCommand(String input) {
        return SlashCommandRouter.resolve(input);
    }

    static String formatTokens(int tokens) {
        if (tokens < 1000) return tokens + "t";
        return String.format("%.1fkt", tokens / 1000.0);
    }

    /**
     * 斜杠命令路由分派。从 REPL 主循环提取为独立方法。
     * @return true 表示退出应用，false 继续循环
     */
    private boolean dispatchCommand(String input) {
        var command = SlashCommandRouter.parse(input);
        if (command == null) {
            System.out.println("未知命令，输入 / 查看菜单。\n");
            return false;
        }
        String args = command.arguments();
        if (commandHandlers.handle(command)) return false;

        switch (command.name()) {
            case "menu" -> printMenu(engine.thinkingMode(), engine.permissionMode());
            case "help" -> printHelp(engine.thinkingMode(), engine.permissionMode());
            case "exit" -> {
                if (mcpManager != null) mcpManager.shutdown();
                System.out.println("Goodbye.");
                return true;
            }
            case "thinking" -> toggleThinking(engine);
            case "plan" -> setPermissionMode(engine, PermissionMode.PLAN);
            case "ask" -> setPermissionMode(engine, PermissionMode.ASK);
            case "auto" -> setPermissionMode(engine, PermissionMode.AUTO);
            case "plan-exec" -> togglePlanExec(engine);
            case "clear" -> {
                engine.clearSession();
                System.out.println(ConsoleRenderer.GRAY + "  session cleared." + ConsoleRenderer.RESET + "\n");
                log.info("Session cleared");
            }
            case "feishu-on" -> startImChannel("feishu", reader);
            case "feishu-off" -> stopImChannel("feishu");
            case "im-on" -> {
                if (!args.isBlank()) startImChannel(args, reader);
                else System.out.println(ConsoleRenderer.GRAY + "  Usage: /im-on feishu|weixin" + ConsoleRenderer.RESET + "\n");
            }
            case "im-off" -> {
                if (!args.isBlank()) stopImChannel(args);
                else stopAllImChannels();
            }
            case "im-status" -> printImStatus();
            case "compact" -> {
                String stats = engine.compactSession();
                System.out.println(ConsoleRenderer.GRAY + "  " + stats + ConsoleRenderer.RESET + "\n");
                log.info("Manual compaction: {}", stats);
            }
            case "context" -> printContext(engine);
            case "config" -> printConfig();
            default -> System.out.println("未知命令，输入 / 查看菜单。\n");
        }
        return false;
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
        System.out.println(ConsoleRenderer.GRAY + "  /thinking   toggle thinking     /plan-exec plan+execute" + ConsoleRenderer.RESET);
        System.out.println(ConsoleRenderer.GRAY + "  /plan       read-only mode      (current: " + perm + ")" + ConsoleRenderer.RESET);
        System.out.println(ConsoleRenderer.GRAY + "  /ask        confirm writes      /auto   full-auto" + ConsoleRenderer.RESET);
        System.out.println(ConsoleRenderer.GRAY + "  /clear      reset session       /compact compress" + ConsoleRenderer.RESET);
        System.out.println(ConsoleRenderer.GRAY + "  /context    token usage         /remember add memory" + ConsoleRenderer.RESET);
        System.out.println(ConsoleRenderer.GRAY + "  /config     effective non-secret configuration" + ConsoleRenderer.RESET);
        System.out.println(ConsoleRenderer.GRAY + "  /runs       recent runs         /metrics run summary" + ConsoleRenderer.RESET);
        System.out.println(ConsoleRenderer.GRAY + "  /trace      run event trace     /memory  list memories" + ConsoleRenderer.RESET);
        System.out.println(ConsoleRenderer.GRAY + "  /session    manage sessions     /skill load/unload" + ConsoleRenderer.RESET);
        System.out.println(ConsoleRenderer.GRAY + "  /mcp        MCP servers         /im-on /im-off /im-status" + ConsoleRenderer.RESET);
        System.out.println(ConsoleRenderer.GRAY + "  /help       show all commands   /exit  quit" + ConsoleRenderer.RESET);
        System.out.println();
    }

    private void printHelp(ThinkingMode thinking, PermissionMode perm) {
        System.out.println();
        System.out.println("  clawkit v" + BuildInfo.version() + " — your local AI coding companion");
        System.out.println();
        System.out.println("  COMMANDS");
        System.out.println("    /thinking    toggle slow thinking mode  (current: " + thinking + ")");
        System.out.println("    /plan        read-only tools only       (current: " + perm + ")");
        System.out.println("    /ask         confirm before each write tool");
        System.out.println("    /auto        execute all tools without confirmation");
        System.out.println("    /clear       reset conversation session");
        System.out.println("    /compact     manually compress context (L1/L2)");
        System.out.println("    /context     show token usage and session info");
        System.out.println("    /config      show effective non-secret configuration");
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
        System.out.println(ConsoleRenderer.GRAY + "  thinking: " + current + " -> " + next + ConsoleRenderer.RESET + "\n");
        log.info("Thinking mode toggled: {} -> {}", current, next);
    }

    private void setPermissionMode(AgentEngine engine, PermissionMode mode) {
        PermissionMode old = engine.permissionMode();
        engine.setPermissionMode(mode);
        System.out.println(ConsoleRenderer.GRAY + "  permission: " + old + " -> " + mode + ConsoleRenderer.RESET + "\n");
        log.info("Permission mode switched: {} -> {}", old, mode);
    }

    private void togglePlanExec(AgentEngine engine) {
        boolean isPlanExec = engine.executionMode() == ExecutionMode.PLAN_EXECUTE;
        if (isPlanExec) {
            engine.setExecutionMode(ExecutionMode.REACT);
            System.out.println(ConsoleRenderer.GRAY + "  execution mode: PLAN_EXECUTE -> REACT" + ConsoleRenderer.RESET + "\n");
        } else {
            engine.setExecutionMode(ExecutionMode.PLAN_EXECUTE);
            engine.setOnPlanReady(this::confirmPlan);
            System.out.println(ConsoleRenderer.GRAY + "  execution mode: REACT -> PLAN_EXECUTE" + ConsoleRenderer.RESET + "\n");
        }
    }

    private boolean confirmPlan(ExecutionPlan plan) {
        System.out.println();
        System.out.println(ConsoleRenderer.GRAY + "  ╔══════════════════════════════════════╗" + ConsoleRenderer.RESET);
        System.out.println(ConsoleRenderer.GRAY + "  ║        Execution Plan                ║" + ConsoleRenderer.RESET);
        System.out.println(ConsoleRenderer.GRAY + "  ╚══════════════════════════════════════╝" + ConsoleRenderer.RESET);
        System.out.println("  Goal: " + plan.getGoal());
        System.out.println();

        List<List<String>> levels = PlanParser.computeLevels(plan.getTasks());

        for (int i = 0; i < levels.size(); i++) {
            System.out.println(ConsoleRenderer.GRAY + "  Level " + i + ConsoleRenderer.RESET);
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
                    System.out.println(ConsoleRenderer.GRAY + "      depends on: " + String.join(", ", task.getDependencies()) + ConsoleRenderer.RESET);
                }
            }
            System.out.println();
        }

        System.out.print(ConsoleRenderer.GRAY + "  Execute this plan? [Y/n] " + ConsoleRenderer.RESET);
        try {
            String line = reader.readLine("");
            if (line == null) return false;
            String input = line.trim().toLowerCase();
            return input.isEmpty() || "y".equals(input) || "yes".equals(input);
        } catch (Exception e) {
            return false;
        }
    }

    // === 回调 ===

    static final String GRAY = "\033[90m";
    static final String GREEN = "\033[32m";
    static final String YELLOW = "\033[33m";
    static final String RED = "\033[31m";
    static final String RESET = "\033[0m";

    private static final String THINKING_BAR = "─".repeat(54);

    private void onThinkingBegin() {
        System.out.print("\n");
        System.out.println(ConsoleRenderer.GRAY + "┌─ thinking " + THINKING_BAR);
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
        System.out.print("\n" + ConsoleRenderer.GRAY + "└" + THINKING_BAR + ConsoleRenderer.RESET + "\n");
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
        System.out.println(boxLine(W, center("v" + BuildInfo.version(), W)));
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

    private void startImBot(String channelName) {
        // P0-R: IM 模式也走 Bootstrap
        Path workDir = resolvedWorkDir != null ? resolvedWorkDir : Path.of(System.getProperty("user.dir"));
        var ctx = ApplicationBootstrap.bootstrap(model, baseUrl, protocol, false, workDir);
        AgentEngine eng = ctx.engine();
        eng.setPermissionMode(PermissionMode.AUTO);

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
            System.out.println(ConsoleRenderer.GRAY + "  " + existing.name() + " is already running." + ConsoleRenderer.RESET + "\n");
            return;
        }

        try {
            ImChannel channel = switch (name) {
                case "feishu" -> {
                    var fc = new FeishuChannel(FeishuConfig.fromEnv());
                    fc.setOnImInput(text -> {
                        System.out.print("\r\033[K" + ConsoleRenderer.GRAY + "  [飞书] " + text + ConsoleRenderer.RESET + "\n> ");
                        System.out.flush();
                    });
                    yield fc;
                }
                case "weixin" -> {
                    var wc = new WeixinChannel(WeixinConfig.fromEnv());
                    wc.setOnImInput(text -> {
                        System.out.print("\r\033[K" + ConsoleRenderer.GRAY + "  [微信] " + text + ConsoleRenderer.RESET + "\n> ");
                        System.out.flush();
                    });
                    yield wc;
                }
                default -> throw new IllegalArgumentException("Unknown channel: " + name);
            };
            channel.setEngine(engine);
            channel.start();
            imChannels.add(channel);
            System.out.println(ConsoleRenderer.GRAY + "  " + channel.name() + " channel started." + ConsoleRenderer.RESET + "\n");
        } catch (Exception e) {
            log.error("Failed to start {} channel: {}", name, e.getMessage(), e);
            System.out.println(ConsoleRenderer.GRAY + "  [C-003] Failed: " + e.getMessage() + ConsoleRenderer.RESET + "\n");
        }
    }

    private void stopImChannel(String name) {
        ImChannel channel = imChannels.stream()
            .filter(c -> c.id().equals(name) && c.isRunning())
            .findFirst().orElse(null);
        if (channel == null) {
            System.out.println(ConsoleRenderer.GRAY + "  " + name + " is not running." + ConsoleRenderer.RESET + "\n");
            return;
        }
        channel.stop();
        imChannels.remove(channel);
        System.out.println(ConsoleRenderer.GRAY + "  " + channel.name() + " channel stopped." + ConsoleRenderer.RESET + "\n");
    }

    private void stopAllImChannels() {
        if (imChannels.isEmpty()) {
            System.out.println(ConsoleRenderer.GRAY + "  No IM channels active." + ConsoleRenderer.RESET + "\n");
            return;
        }
        for (var ch : imChannels) {
            if (ch.isRunning()) ch.stop();
        }
        imChannels.clear();
        System.out.println(ConsoleRenderer.GRAY + "  All IM channels stopped." + ConsoleRenderer.RESET + "\n");
    }

    private void printImStatus() {
        if (imChannels.isEmpty()) {
            System.out.println(ConsoleRenderer.GRAY + "  No IM channels active." + ConsoleRenderer.RESET + "\n");
            return;
        }
        System.out.println();
        for (var ch : imChannels) {
            var s = ch.status();
            String icon = s.running() ? "*" : " ";
            String user = s.linkedUser() != null ? s.linkedUser() : "—";
            System.out.println(ConsoleRenderer.GRAY + "  [" + icon + "] " + padRight(s.name(), 8)
                + "  user: " + user + "  " + s.stateInfo() + ConsoleRenderer.RESET);
        }
        System.out.println();
    }

    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new ClawkitApp());
        commandLine.setExecutionExceptionHandler((error, cmd, parseResult) -> {
            if (error instanceof ConfigurationException configuration) {
                CliErrorRenderer.render(configuration);
                return 2;
            }
            String diagnosticId = CliErrorRenderer.renderUnexpected(error);
            LoggerFactory.getLogger(ClawkitApp.class)
                .error("[{}] Unexpected startup failure: {}", diagnosticId, error.getMessage(), error);
            return 4;
        });
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    private void printConfig() {
        if (effectiveConfig == null) return;
        System.out.println();
        printSetting("provider", "deepseek", "fixed");
        printSetting("model", effectiveConfig.model(), effectiveConfig.sources().get("model"));
        printSetting("endpoint", effectiveConfig.baseUrl(), effectiveConfig.sources().get("baseUrl"));
        printSetting("requestTimeout", effectiveConfig.requestTimeoutSeconds() + "s",
            effectiveConfig.sources().get("requestTimeoutSeconds"));
        printSetting("maxRetries", Integer.toString(effectiveConfig.maxRetries()),
            effectiveConfig.sources().get("maxRetries"));
        printSetting("apiKey", effectiveConfig.apiKeyConfigured() ? "SET" : "NOT SET",
            "env:CLAWKIT_API_KEY");
        printSetting("workdir", resolvedWorkDir.toString(), rootDir == null ? "default" : "cli");
        System.out.println();
    }

    private static void printSetting(String name, String value, String source) {
        System.out.printf("  %-16s %-42s [%s]%n", name, value, source);
    }
}
