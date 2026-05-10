package com.miniclaw.cli;

import com.miniclaw.engine.PermissionMode;
import com.miniclaw.engine.ThinkingMode;
import com.miniclaw.engine.impl.AgentEngine;
import com.miniclaw.provider.LLMConfig;
import com.miniclaw.provider.impl.openai.OpenAIProvider;
import com.miniclaw.tools.ToolRegistry;
import com.miniclaw.tools.impl.BashTool;
import com.miniclaw.tools.impl.EditTool;
import com.miniclaw.tools.impl.GlobTool;
import com.miniclaw.tools.impl.GrepTool;
import com.miniclaw.tools.impl.ReadTool;
import com.miniclaw.tools.impl.WriteTool;
import java.nio.file.Path;
import java.util.Scanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
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

    @Override
    public void run() {
        String apiKey = System.getenv("MINICLAW_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("ANTHROPIC_AUTH_TOKEN");
        }
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[C-003] MINICLAW_API_KEY environment variable not set.");
            System.exit(1);
        }

        LLMConfig config = LLMConfig.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .model(model)
            .build();

        OpenAIProvider provider = new OpenAIProvider(config);

        Path workDir = Path.of(System.getProperty("user.dir"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadTool(workDir));
        registry.register(new WriteTool(workDir));
        registry.register(new BashTool(workDir));
        registry.register(new EditTool(workDir));
        registry.register(new GlobTool(workDir));
        registry.register(new GrepTool(workDir));

        ThinkingMode mode = thinking ? ThinkingMode.TWO_STAGE : ThinkingMode.OFF;
        AgentEngine engine = new AgentEngine(provider, registry, workDir.toString(),
            mode, null, System.out::print);
        if (mode == ThinkingMode.TWO_STAGE) {
            engine.setOnThinkingBegin(this::onThinkingBegin);
            engine.onReasoning(this::onReasoning);
        }

        try (Scanner scanner = new Scanner(System.in)) {
            engine.setPermissionHandler(call -> {
                String args = call.arguments() != null ? call.arguments().toString() : "{}";
                if (args.length() > 120) {
                    args = args.substring(0, 120) + "...";
                }
                System.out.print(GRAY + "  Execute " + call.name() + " " + args + "? [y/n] " + RESET);
                String answer = scanner.nextLine().trim().toLowerCase();
                return answer.equals("y") || answer.equals("yes");
            });

        log.info("miniclaw started — model={}, thinking={}", model, mode);

        printBanner(model, registry.count());

        while (true) {
            System.out.print("> ");
            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine();
            if (input.isBlank()) continue;

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
                    default -> System.out.println("未知命令，输入 / 查看菜单。\n");
                }
                continue;
            }

            try {
                String result = engine.run(input);
                // 流式模式：内容已通过 onToken 实时输出；仅错误码需额外打印
                if (result.startsWith("[")) {
                    System.out.println("\n" + result + "\n");
                } else {
                    System.out.println("\n");
                }
            } catch (Exception e) {
                log.error("Engine error: {}", e.getMessage(), e);
                System.err.println("[A-003] " + e.getMessage());
            }
        }

        } // try-with-resources closes scanner

        log.info("miniclaw exiting normally.");
        System.out.println("Goodbye.");
    }

    // === 命令处理 ===

    private void printMenu(ThinkingMode thinking, PermissionMode perm) {
        System.out.println();
        System.out.println(GRAY + "  /t  toggle thinking     (current: " + thinking + ")" + RESET);
        System.out.println(GRAY + "  /p  plan mode           (current: " + perm + ")" + RESET);
        System.out.println(GRAY + "  /a  ask mode   /auto    auto mode" + RESET);
        System.out.println(GRAY + "  /h  help       /q       quit" + RESET);
        System.out.println();
    }

    private void printHelp(ThinkingMode thinking, PermissionMode perm) {
        System.out.println();
        System.out.println("  /t, /thinking   切换慢思考模式 (当前: " + thinking + ")");
        System.out.println("  /p, /plan       仅允许读工具，强制 LLM 先规划 (当前: " + perm + ")");
        System.out.println("  /a, /ask        写工具执行前人工确认");
        System.out.println("  /auto           全自动执行所有工具");
        System.out.println("  /h, /help       显示此帮助");
        System.out.println("  /q, /exit       退出程序");
        System.out.println("  输入 / 可查看快捷菜单。\n");
    }

    private void toggleThinking(AgentEngine engine) {
        ThinkingMode current = engine.thinkingMode();
        ThinkingMode next = current == ThinkingMode.TWO_STAGE
            ? ThinkingMode.OFF : ThinkingMode.TWO_STAGE;
        engine.setThinkingMode(next);
        engine.setOnThinkingBegin(next == ThinkingMode.TWO_STAGE
            ? this::onThinkingBegin : null);
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
        System.out.print("\n"); // 确保与上一轮流式输出之间有换行
        System.out.println(GRAY + "┌─ thinking " + THINKING_BAR + RESET);
    }

    private void onReasoning(String text) {
        if (text != null && !text.isEmpty()) {
            for (String line : text.split("\n")) {
                System.out.println(GRAY + "│ " + line + RESET);
            }
        }
        System.out.println(GRAY + "└" + THINKING_BAR + RESET);
        System.out.println();
    }

    private void printBanner(String model, int toolCount) {
        System.out.println();
        System.out.println("  ☆  ☆  ☆");
        System.out.println("    /\\_/\\");
        System.out.println("   ( ^.^ )      miniclaw v0.1.0");
        System.out.println("    >   <  nya~ your local AI coding companion");
        System.out.println();
        System.out.println("+-----------------------------------------------+");
        System.out.println("|  model   " + String.format("%-36s", model) + "|");
        System.out.println("|  tools   " + toolCount + " loaded                                |");
        System.out.println("|  /   show menu     /t   toggle thinking     |");
        System.out.println("|  /p  plan mode     /a   ask   /auto  auto   |");
        System.out.println("|  /h  help           /q   quit               |");
        System.out.println("+-----------------------------------------------+");
        System.out.println();
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MiniclawApp()).execute(args);
        System.exit(exitCode);
    }
}
