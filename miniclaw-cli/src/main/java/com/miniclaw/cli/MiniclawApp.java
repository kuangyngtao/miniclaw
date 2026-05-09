package com.miniclaw.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "miniclaw",
    description = "A minimal AI coding assistant",
    mixinStandardHelpOptions = true,
    version = "miniclaw 0.1.0"
)
public class MiniclawApp implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MiniclawApp.class);

    @Override
    public void run() {
        System.out.println("miniclaw — arch skeleton ready, waiting for core modules.");

        // TODO: 1. 初始化大模型 Provider (大脑)
        // LLMClient llmClient = ClaudeClient.create(config);

        // TODO: 2. 初始化 Tool Registry (手脚)
        // ToolRegistry registry = new ToolRegistry();
        // registry.register(new ReadTool());
        // registry.register(new WriteTool());
        // registry.register(new EditTool());
        // registry.register(new BashTool());
        // registry.register(new GlobTool());
        // registry.register(new GrepTool());

        // TODO: 3. 初始化上下文管理器 (内存管理器)
        // ContextManager ctx = ContextManager.create(config);

        // TODO: 4. 初始化 Memory 存储
        // MemoryStore memory = new FileMemoryStore(config.getDataDir());

        // TODO: 5. 组装并启动核心 Engine (心脏)
        // AgentLoop loop = AgentLoop.builder()
        //         .llmClient(llmClient)
        //         .toolRegistry(registry)
        //         .contextManager(ctx)
        //         .memoryStore(memory)
        //         .build();
        // loop.start();

        log.info("Arch skeleton assembled. Awaiting core module injection.");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MiniclawApp()).execute(args);
        System.exit(exitCode);
    }
}
