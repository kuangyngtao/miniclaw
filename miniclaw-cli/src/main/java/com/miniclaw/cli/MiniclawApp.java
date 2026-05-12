package com.miniclaw.cli;

import com.miniclaw.engine.PermissionMode;
import com.miniclaw.engine.ThinkingMode;
import com.miniclaw.engine.impl.AgentEngine;
import com.miniclaw.feishu.FeishuBot;
import com.miniclaw.feishu.FeishuConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.memory.MemoryEntry;
import com.miniclaw.memory.MemoryType;
import com.miniclaw.memory.impl.DiskMemoryService;
import com.miniclaw.provider.LLMConfig;
import com.miniclaw.provider.LLMProvider;
import com.miniclaw.provider.impl.openai.OpenAIProvider;
import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.ToolRegistry;
import com.miniclaw.tools.impl.BashTool;
import com.miniclaw.tools.impl.CommandSafetyInterceptor;
import com.miniclaw.tools.impl.EditTool;
import com.miniclaw.tools.impl.GlobTool;
import com.miniclaw.tools.impl.GrepTool;
import com.miniclaw.tools.impl.ReadTool;
import com.miniclaw.tools.impl.TodoWriteTool;
import com.miniclaw.tools.impl.WebFetchTool;
import com.miniclaw.tools.impl.WriteTool;
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
    name = "miniclaw",
    description = "A minimal AI coding assistant",
    mixinStandardHelpOptions = true,
    version = "miniclaw 0.1.0"
)
public class MiniclawApp implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MiniclawApp.class);

    @Option(names = {"-m", "--model"}, defaultValue = "deepseek-chat",
        description = "Model name (default: deepseek-chat)")
    private String model;

    @Option(names = {"--base-url"}, defaultValue = "https://api.deepseek.com",
        description = "API base URL")
    private String baseUrl;

    @Option(names = {"--thinking"}, description = "Enable slow thinking mode (TWO_STAGE)")
    private boolean thinking;

    @Option(names = {"--feishu"}, description = "Start in Feishu bot mode")
    private boolean feishu;

    @Option(names = {"--root"}, description = "Restrict working directory (default: current dir)")
    private Path rootDir;

    private Path resolvedWorkDir;
    private AgentEngine engine;
    private FeishuBot feishuBot;

    @Override
    public void run() {
        if (feishu) {
            startFeishuBot();
            return;
        }

        resolvedWorkDir = resolveWorkDir(rootDir);

        String apiKey = readConfigValue("MINICLAW_API_KEY", "ANTHROPIC_AUTH_TOKEN", "apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[C-003] MINICLAW_API_KEY not set (env or ~/.miniclaw/config.yaml).");
            System.exit(1);
        }

        LLMConfig config = LLMConfig.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .model(model)
            .build();

        OpenAIProvider provider = new OpenAIProvider(config);

        ToolRegistry registry = createToolRegistry(resolvedWorkDir);

        ThinkingMode mode = thinking ? ThinkingMode.TWO_STAGE : ThinkingMode.OFF;
        Path memoryDir = Path.of(System.getProperty("user.home"), ".miniclaw", "memory");
        DiskMemoryService memoryService = new DiskMemoryService(memoryDir);
        String memoryIndex = memoryService.loadIndex();

        // 读取项目级 CLAUDE.md / AGENTS.md
        String projectContext = readProjectContext(resolvedWorkDir);
        String fullContext = buildFullContext(projectContext, memoryIndex);

        engine = new AgentEngine(provider, registry, resolvedWorkDir.toString(),
            mode, null, System.out::print, fullContext);

        if (mode == ThinkingMode.TWO_STAGE) {
            engine.setOnThinkingBegin(this::onThinkingBegin);
            engine.onThinkingToken(this::onThinkingToken);
            engine.onReasoning(this::onReasoning);
        }

        Path historyPath = Path.of(System.getProperty("user.home"), ".miniclaw", "history");
        LineReader reader;
        try {
            reader = LineReaderBuilder.builder()
                .terminal(TerminalBuilder.terminal())
                .completer(new MiniclawCompleter())
                .variable(LineReader.HISTORY_FILE, historyPath)
                .build();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to initialize terminal", e);
        }

        // JLine3 跨平台 Ctrl+C 信号处理（替代 sun.misc.Signal）
        reader.getTerminal().handle(Terminal.Signal.INT, signal -> {
            engine.interrupt();
        });

        engine.setPermissionHandler(call -> {
            String args = call.arguments() != null ? call.arguments().toString() : "{}";
            if (args.length() > 120) {
                args = args.substring(0, 120) + "...";
            }
            String prompt = GRAY + "  Execute " + call.name() + " " + args + "? [y/n] " + RESET;
            String answer = reader.readLine(prompt).trim().toLowerCase();
            return answer.equals("y") || answer.equals("yes");
        });

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

        log.info("miniclaw started — model={}, thinking={}", model, mode);

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
                handleRemember(reader, memoryService, provider, engine, projectContext, input);
                continue;
            }
            if (input.startsWith("/memory")) {
                handleMemory(input, memoryService, engine, projectContext);
                continue;
            }

            // === 斜杠命令 ===
            if (input.startsWith("/")) {
                switch (input) {
                    case "/" -> printMenu(engine.thinkingMode(), engine.permissionMode());
                    case "/h", "/help" -> printHelp(engine.thinkingMode(), engine.permissionMode());
                    case "/q", "/exit" -> {
                        System.out.println("Goodbye.");
                        return;
                    }
                    case "/t", "/thinking" -> toggleThinking(engine);
                    case "/p", "/plan" -> setPermissionMode(engine, PermissionMode.PLAN);
                    case "/a", "/ask" -> setPermissionMode(engine, PermissionMode.ASK);
                    case "/auto" -> setPermissionMode(engine, PermissionMode.AUTO);
                    case "/c", "/clear" -> {
                        engine.clearSession();
                        System.out.println(GRAY + "  session cleared." + RESET + "\n");
                        log.info("Session cleared");
                    }
                    case "/feishu-on" -> startFeishuCompanion(reader);
                    case "/feishu-off" -> stopFeishuCompanion();
                    case "/compact" -> {
                        String stats = engine.compactSession();
                        System.out.println(GRAY + "  " + stats + RESET + "\n");
                        log.info("Manual compaction: {}", stats);
                    }
                    case "/context" -> {
                        printContext(engine);
                    }
                    default -> System.out.println("未知命令，输入 / 查看菜单。\n");
                }
                continue;
            }

            if (!engine.tryAcquire()) {
                System.out.println(GRAY + "  Engine busy (Feishu is processing)...\n" + RESET);
                continue;
            }
            try {
                if (feishuBot != null && feishuBot.isRunning()) {
                    feishuBot.mirrorToFeishu(input);
                }
                String result = engine.run(input);
                if (feishuBot != null && feishuBot.isRunning()) {
                    feishuBot.finalizeMirror(result);
                }
                if (result.startsWith("[")) {
                    System.out.println("\n" + result + "\n");
                } else {
                    System.out.println("\n");
                }
            } catch (Exception e) {
                log.error("Engine error: {}", e.getMessage(), e);
                System.err.println("[A-003] " + e.getMessage());
                if (feishuBot != null && feishuBot.isRunning()) {
                    feishuBot.finalizeMirror(null);
                }
            } finally {
                engine.release();
            }
        }

        log.info("miniclaw exiting normally.");
        System.out.println("Goodbye.");
    }

    private void printContext(AgentEngine engine) {
        int tokens = engine.getEstimatedTokens();
        int maxTokens = 8000;
        int pct = maxTokens > 0 ? tokens * 100 / maxTokens : 0;
        String bar = "░".repeat(10);
        int filled = pct * 10 / 100;
        bar = "█".repeat(filled) + "░".repeat(10 - filled);
        System.out.println();
        System.out.println(GRAY + "  context  [" + bar + "] " + pct + "%  (~" + formatTokens(tokens) + " / " + formatTokens(maxTokens) + ")" + RESET);
        System.out.println(GRAY + "  mode     " + engine.thinkingMode() + " / " + engine.permissionMode() + RESET);
        System.out.println(GRAY + "  workdir  " + engine.workDir() + RESET);
        System.out.println();
    }

    private static String formatTokens(int tokens) {
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
                                String projectContext, String input) {
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
        engine.setMemoryIndex(buildFullContext(projectContext, service.loadIndex()));
        System.out.println(GRAY + "\r  saved " + entry.filename()
            + " [" + type.name().toLowerCase() + "] " + description + RESET + "\n");
    }

    private void handleMemory(String input, DiskMemoryService service,
                              AgentEngine engine, String projectContext) {
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
            engine.setMemoryIndex(buildFullContext(projectContext, service.loadIndex()));
            System.out.println(GRAY + "  Index regenerated from files." + RESET + "\n");
        } else {
            System.out.println(GRAY + "  Usage: /memory list | /memory regen" + RESET + "\n");
        }
    }

    private static String readProjectContext(Path workDir) {
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

    private static String buildFullContext(String projectContext, String memoryIndex) {
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

    private static MemoryType parseMemoryType(String s) {
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
        System.out.println(GRAY + "  /thinking   toggle thinking     (current: " + thinking + ")" + RESET);
        System.out.println(GRAY + "  /plan       read-only mode      (current: " + perm + ")" + RESET);
        System.out.println(GRAY + "  /ask        confirm writes      /auto   full-auto" + RESET);
        System.out.println(GRAY + "  /clear      reset session       /compact compress" + RESET);
        System.out.println(GRAY + "  /context    token usage         /remember add memory" + RESET);
        System.out.println(GRAY + "  /memory     list memories       /feishu-on  /feishu-off" + RESET);
        System.out.println(GRAY + "  /help       /exit" + RESET);
        System.out.println();
    }

    private void printHelp(ThinkingMode thinking, PermissionMode perm) {
        System.out.println();
        System.out.println("  miniclaw v0.1.0 — your local AI coding companion");
        System.out.println();
        System.out.println("  COMMANDS");
        System.out.println("    /thinking    toggle slow thinking mode  (current: " + thinking + ")");
        System.out.println("    /plan        read-only tools only       (current: " + perm + ")");
        System.out.println("    /ask         confirm before each write tool");
        System.out.println("    /auto        execute all tools without confirmation");
        System.out.println("    /clear       reset conversation session");
        System.out.println("    /compact     manually compress context (L1/L2)");
        System.out.println("    /context     show token usage and session info");
        System.out.println("    /remember    add a memory entry (interactive)");
        System.out.println("    /memory      list or manage memory entries");
        System.out.println("    /feishu-on   enable Feishu bot companion");
        System.out.println("    /feishu-off  disable Feishu bot companion");
        System.out.println("    /help        show this help");
        System.out.println("    /exit        quit miniclaw");
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

    // === 回调 ===

    private static final String GRAY = "\033[90m";
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
        String toolsLine = toolCount + " loaded (read,write,edit,bash,glob,grep,todo_write,web_fetch)";
        System.out.println(boxLine(W, "  tools     " + padRight(truncatePath(toolsLine, W - 12), W - 12)));
        System.out.println(boxLine(W, "", '╠', '╣'));
        System.out.println(boxLine(W, "  /help     show all commands"));
        System.out.println(boxLine(W, "  /plan     read-only mode   /ask     confirm writes"));
        System.out.println(boxLine(W, "  /auto     full-auto        /clear   reset session"));
        System.out.println(boxLine(W, "  /thinking toggle thinking  /compact compress context"));
        System.out.println(boxLine(W, "  /context  show session     /exit    quit"));
        System.out.println(boxLine(W, "═".repeat(W), '╚', '╝'));
        System.out.println();
        System.out.println("  Try \"explain this project\" or \"fix the NPE in UserService.java\"");
        System.out.println();
    }

    private static String boxLine(int width, String content) {
        return boxLine(width, content, '║', '║');
    }

    private static String boxLine(int width, String content, char left, char right) {
        if (content.length() > width) content = content.substring(0, width);
        else content = padRight(content, width);
        return "  " + left + content + right;
    }

    private static String center(String s, int width) {
        if (s.length() >= width) return s;
        int pad = (width - s.length()) / 2;
        return " ".repeat(pad) + s;
    }

    private static String padRight(String s, int n) {
        if (s.length() >= n) return s;
        return s + " ".repeat(n - s.length());
    }

    private static String truncatePath(String path, int maxLen) {
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
        return FeishuConfig.readConfig(configPath);
    }

    private void startFeishuBot() {
        String apiKey = readConfigValue("MINICLAW_API_KEY", "ANTHROPIC_AUTH_TOKEN", "apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[C-003] MINICLAW_API_KEY not set (env or ~/.miniclaw/config.yaml).");
            System.exit(1);
        }

        LLMConfig llmConfig = LLMConfig.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .model(model)
            .build();

        Path workDir = resolvedWorkDir != null ? resolvedWorkDir : Path.of(System.getProperty("user.dir"));
        OpenAIProvider provider = new OpenAIProvider(llmConfig);
        ToolRegistry registry = createToolRegistry(workDir);
        AgentEngine eng = new AgentEngine(provider, registry, workDir.toString(),
            ThinkingMode.OFF, null, null, null);
        eng.setPermissionMode(PermissionMode.AUTO);

        FeishuConfig feishuConfig = FeishuConfig.fromEnv();
        FeishuBot bot = new FeishuBot(feishuConfig);
        bot.setEngine(eng);
        try {
            bot.start();
            bot.awaitShutdown();
        } catch (java.io.IOException | InterruptedException e) {
            log.error("Failed to start Feishu bot: {}", e.getMessage(), e);
            System.err.println("[C-003] Failed to start Feishu bot: " + e.getMessage());
            System.exit(1);
        }
    }

    private void startFeishuCompanion(LineReader reader) {
        if (feishuBot != null && feishuBot.isRunning()) {
            System.out.println(GRAY + "  Feishu companion is already running." + RESET + "\n");
            return;
        }
        try {
            FeishuConfig feishuConfig = FeishuConfig.fromEnv();
            feishuBot = new FeishuBot(feishuConfig);
            feishuBot.setEngine(engine);
            feishuBot.setOnFeishuInput(text -> {
                System.out.print("\r\033[K" + GRAY + "  [飞书] " + text + RESET + "\n> ");
                System.out.flush();
            });
            feishuBot.start();
            System.out.println(GRAY + "  Feishu companion started. Send a message from Feishu to link." + RESET + "\n");
        } catch (Exception e) {
            log.error("Failed to start Feishu companion: {}", e.getMessage(), e);
            System.out.println(GRAY + "  [C-003] Failed: " + e.getMessage() + RESET + "\n");
        }
    }

    private void stopFeishuCompanion() {
        if (feishuBot == null || !feishuBot.isRunning()) {
            System.out.println(GRAY + "  Feishu companion is not running." + RESET + "\n");
            return;
        }
        feishuBot.stop();
        feishuBot = null;
        System.out.println(GRAY + "  Feishu companion stopped." + RESET + "\n");
    }

    private ToolRegistry createToolRegistry(Path workDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadTool(workDir));
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
        int exitCode = new CommandLine(new MiniclawApp()).execute(args);
        System.exit(exitCode);
    }
}
