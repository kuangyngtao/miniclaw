package com.clawkit.cli;

import com.clawkit.engine.SessionService;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.memory.impl.DiskMemoryService;
import com.clawkit.observability.RunReader;
import com.clawkit.provider.LLMProvider;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.context.SkillLoader;
import com.clawkit.tools.mcp.McpManager;
import com.clawkit.im.ImChannel;

import java.nio.file.Path;
import java.util.List;
import org.jline.reader.LineReader;

/**
 * 应用装配产物。由 ApplicationBootstrap 创建，供 ReplLoop/Router/Renderer 使用。
 */
public record ApplicationContext(
    AgentEngine engine,
    LLMProvider provider,
    ToolRegistry registry,
    SessionService sessionService,
    SkillLoader skillLoader,
    McpManager mcpManager,
    DiskMemoryService memoryService,
    RunReader runReader,
    LineReader reader,
    List<ImChannel> imChannels,
    Path workDir,
    String model,
    ThinkingMode thinkingMode,
    EffectiveConfig effectiveConfig
) {}
