package com.miniclaw.cli;

import com.miniclaw.engine.AgentLoop;
import com.miniclaw.engine.ThinkingMode;
import com.miniclaw.engine.impl.AgentEngine;
import com.miniclaw.provider.LLMConfig;
import com.miniclaw.provider.impl.openai.OpenAIProvider;
import com.miniclaw.tools.ToolRegistry;
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
            System.err.println("[C-003] MINICLAW_API_KEY environment variable not set.");
            System.exit(1);
        }

        LLMConfig config = LLMConfig.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .model(model)
            .build();

        OpenAIProvider provider = new OpenAIProvider(config);
        ToolRegistry registry = new ToolRegistry();
        ThinkingMode mode = thinking ? ThinkingMode.TWO_STAGE : ThinkingMode.OFF;
        AgentLoop engine = new AgentEngine(provider, registry, System.getProperty("user.dir"),
            mode, mode == ThinkingMode.TWO_STAGE ? this::onReasoning : null);

        log.info("miniclaw started — model={}, thinking={}", model, mode);

        printBanner(model);

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine();
            if (input.isBlank()) continue;
            if ("/exit".equals(input)) break;
            if ("/help".equals(input)) {
                System.out.println("/exit  Quit");
                System.out.println("Otherwise: your prompt is sent to the LLM.\n");
                continue;
            }

            try {
                String result = engine.run(input);
                System.out.println("\n" + result + "\n");
            } catch (Exception e) {
                log.error("Engine error: {}", e.getMessage(), e);
                System.err.println("[A-003] " + e.getMessage());
            }
        }

        log.info("miniclaw exiting normally.");
        System.out.println("Goodbye.");
    }

    private void onReasoning(String text) {
        String firstLine = text.split("\n")[0];
        if (firstLine.length() > 80) {
            firstLine = firstLine.substring(0, 80) + "...";
        }
        System.out.println("[思考] " + firstLine);
    }

    private void printBanner(String model) {
        System.out.println();
        System.out.println("  ☆  ☆  ☆");
        System.out.println("    /\\_/\\");
        System.out.println("   ( ^.^ )      miniclaw v0.1.0");
        System.out.println("    >   <  nya~ your local AI coding companion");
        System.out.println();
        System.out.println("+-------------------------------------------+");
        System.out.println("|  model   " + String.format("%-32s", model) + "|");
        System.out.println("|  tools   0 loaded                        |");
        System.out.println("|  /help   show commands                   |");
        System.out.println("|  /exit   quit                            |");
        System.out.println("+-------------------------------------------+");
        System.out.println();
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MiniclawApp()).execute(args);
        System.exit(exitCode);
    }
}
