package com.clawkit.engine.impl;

import com.clawkit.context.CompactionResult;
import com.clawkit.context.ContextBudgetAnalyzer;
import com.clawkit.context.ContextBudgetPolicy;
import com.clawkit.context.ContextBudgetReport;
import com.clawkit.context.ContextManager;
import com.clawkit.context.PromptAssembly;
import com.clawkit.context.SkillCatalog;
import com.clawkit.context.SkillLoader;
import com.clawkit.context.Tokenizer;
import com.clawkit.context.impl.LadderedCompactor;
import com.clawkit.context.impl.MessageMasker;
import com.clawkit.context.impl.MessageMasker.MaskedContext;
import com.clawkit.context.impl.TokenizerFactory;
import com.clawkit.context.impl.TurnGroup;
import java.nio.file.Files;
import java.nio.file.Path;
import com.clawkit.engine.AgentLoop;
import com.clawkit.engine.AgentState;
import com.clawkit.engine.AgentStateEvent;
import com.clawkit.engine.ApprovalHandler;
import com.clawkit.engine.ApprovalRequest;
import com.clawkit.engine.ApprovalResult;
import com.clawkit.engine.PermissionMode;
import com.clawkit.engine.SessionMeta;
import com.clawkit.engine.SessionService;
import com.clawkit.engine.ExecutionMode;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.context.PlannerPrompt;
import com.clawkit.tools.Result;
import com.clawkit.tools.schema.ExecutionPlan;
import com.clawkit.tools.schema.PlanStatus;
import com.clawkit.tools.schema.Task;
import com.clawkit.tools.schema.TaskStatus;
import com.clawkit.memory.MemoryEntry;
import com.clawkit.memory.MemoryType;
import com.clawkit.memory.impl.DiskMemoryService;
import com.clawkit.provider.LLMException;
import com.clawkit.provider.LLMProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawkit.tools.Registry;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.Role;
import com.clawkit.tools.schema.ToolCall;
import com.clawkit.tools.schema.ToolDefinition;
import com.clawkit.tools.schema.ToolResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.clawkit.observability.RunEvent;
import com.clawkit.observability.RunStatus;
import com.clawkit.observability.model.ProviderCallMetrics;
import com.clawkit.observability.model.ToolCallMetrics;
import com.clawkit.observability.model.TurnMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentEngine — clawkit 的核心驱动。
 * 组装 LLMProvider + Registry，驱动 ReAct 循环。
 * 支持两种思考模式：OFF（单阶段）和 TWO_STAGE（先规划再执行）。
 */
public class AgentEngine implements AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    // === SubAgent 事件 record（供 CLI 回调） ===
    public record SubAgentSpawnEvent(String instruction, String type, int maxTurns) {}
    public record SubAgentCompleteEvent(String summary, int turnsUsed, int tokens, long durationMs) {}

    // === Tool 可视化事件 ===
    public record ToolStartEvent(String name, String argSummary) {}
    public record ToolEndEvent(String name, boolean success, String detail) {}

    // ── 5-layer prompt architecture ──────────────────────────────────

    private static final String L1_KERNEL =
        "You are clawkit，一个专业的编程助手。";

    private volatile String l2WorkspaceRules = "";
    private volatile SkillCatalog skillCatalog = SkillCatalog.empty();
    private final Map<String, String> activeSkills = new ConcurrentHashMap<>();
    private volatile String l5MemoryIndex = "";

    private volatile SkillLoader skillLoader;
    private volatile DiskMemoryService diskMemoryService;

    private String buildL4ModePrompt() {
        String prompt = switch (permissionMode) {
            case PLAN -> L1_KERNEL
                + "当前处于 PLAN（只读规划）模式。\n"
                + "\n"
                + "可用工具：read、glob、grep、web_fetch（仅读工具）\n"
                + "不可用：write、edit、bash、todo_write —— 不要尝试调用它们。\n"
                + "\n"
                + "你的工作流程：\n"
                + "1. 探索 —— 使用读工具深入理解代码库结构、现有模式和相关代码路径。\n"
                + "2. 综合 —— 将发现整理为结构化计划。\n"
                + "3. 输出 —— 将计划作为最终文本回复输出（不要尝试写入文件，你没有写工具）。\n"
                + "\n"
                + "计划结构：\n"
                + "## 背景 —— 用户需求是什么，解决什么问题\n"
                + "## 设计方案 —— 实现思路、架构决策、组件关系\n"
                + "## 实施步骤 —— 逐文件说明改动内容\n"
                + "## 验证方法 —— 如何测试（命令、断言、边界情况）\n"
                + "\n"
                + "计划完成后，提醒用户通过 /ask 或 /auto 切换到执行模式来落实计划。";
            case ASK -> L1_KERNEL
                + "当前处于 ASK（询问确认）模式。\n"
                + "\n"
                + "读工具（read、glob、grep、web_fetch）自动执行，无需确认。\n"
                + "写工具（write、edit、bash、todo_write）需要用户确认后才能执行。\n"
                + "\n"
                + "调用写工具之前，简要说明：\n"
                + "- 打算改什么、为什么改\n"
                + "- 涉及哪些文件\n"
                + "\n"
                + "用户会看到确认提示，批准或拒绝每次写操作。\n"
                + "如果被拒绝：说明什么被阻止了，并提出替代方案。\n"
                + "\n"
                + "用户说\"记一下\"/\"记住\"/\"别忘了\"时，立即调用 memory_save 工具保存，不要仅口头确认。\n"
                + "\n"
                + "## 工具使用规范\n"
                + "\n"
                + "### 并行调用\n"
                + "同一轮可发出多个互不依赖的工具调用，系统会并行执行。glob/grep/read 之间通常无依赖，"
                + "可同一轮并行发出；有先后依赖的调用分多轮。\n"
                + "\n"
                + "### 工具选择\n"
                + "- 代码库问题（\"这个类是干什么的\"、\"哪里用了某功能\"）→ glob/grep，不要 web_search\n"
                + "- 训练数据已有的稳定知识（语法、标准库 API）→ 直接回答，不联网\n"
                + "- 时效性/不确定的事实 → web_search 找入口 → web_fetch 拿全文\n"
                + "- 已有具体 URL → 直接 web_fetch，不要先 web_search\n"
                + "\n"
                + "### 网页内容获取\n"
                + "- 静态/SSR 页面（博客、文档、wiki、GitHub README）→ web_fetch\n"
                + "- SPA/客户端渲染（需 JS 才有内容）→ Chrome MCP\n"
                + "- 需登录态/需表单交互 → Chrome MCP\n"
                + "- 微信公众号（mp.weixin.qq.com）→ web_fetch（Chrome MCP 会被微信限制）\n"
                + "- 已知 URL → web_fetch 先试，失败再 Chrome MCP\n"
                + "- Chrome MCP 导航被拦截时 → 提示用户手动导航到目标页面，再用 MCP 操作已加载页面\n"
                + "- 都失败时告知用户用真实浏览器打开";
            case AUTO -> L1_KERNEL
                + "当前处于 AUTO（自动执行）模式，拥有全部工具权限，无需用户确认。\n"
                + "\n"
                + "工作流程：\n"
                + "1. 先读后改 —— 修改前先阅读现有代码，理解上下文。\n"
                + "2. 规划追踪 —— 3 步以上的任务用 todo_write 记录进度。\n"
                + "3. 逐步执行 —— 按计划一步一步修改。\n"
                + "\n"
                + "用 task 工具将独立的子任务（搜索代码、分析模块、隔离实现某个功能）"
                + "委派给子 Agent 以保持上下文干净。\n"
                + "完成后总结做了什么。\n"
                + "\n"
                + "用户说\"记一下\"/\"记住\"/\"别忘了\"时，立即调用 memory_save 工具保存，不要仅口头确认。\n"
                + "\n"
                + "## 工具使用规范\n"
                + "\n"
                + "### 并行调用\n"
                + "同一轮可发出多个互不依赖的工具调用，系统会并行执行。glob/grep/read 之间通常无依赖，"
                + "可同一轮并行发出；有先后依赖的调用分多轮。\n"
                + "\n"
                + "### 工具选择\n"
                + "- 代码库问题（\"这个类是干什么的\"、\"哪里用了某功能\"）→ glob/grep，不要 web_search\n"
                + "- 训练数据已有的稳定知识（语法、标准库 API）→ 直接回答，不联网\n"
                + "- 时效性/不确定的事实 → web_search 找入口 → web_fetch 拿全文\n"
                + "- 已有具体 URL → 直接 web_fetch，不要先 web_search\n"
                + "\n"
                + "### 网页内容获取\n"
                + "- 静态/SSR 页面（博客、文档、wiki、GitHub README）→ web_fetch\n"
                + "- SPA/客户端渲染（需 JS 才有内容）→ Chrome MCP\n"
                + "- 需登录态/需表单交互 → Chrome MCP\n"
                + "- 微信公众号（mp.weixin.qq.com）→ web_fetch（Chrome MCP 会被微信限制）\n"
                + "- 已知 URL → web_fetch 先试，失败再 Chrome MCP\n"
                + "- Chrome MCP 导航被拦截时 → 提示用户手动导航到目标页面，再用 MCP 操作已加载页面\n"
                + "- 都失败时告知用户用真实浏览器打开";
        };

        if (permissionMode != PermissionMode.PLAN && enableSubAgents) {
            prompt += "\n\n## Task Delegation\n\n"
                + "You have access to a `task` tool for delegating self-contained "
                + "subtasks to sub-agents with isolated context windows. "
                + "Use `task` to search many files, analyze multiple modules, "
                + "or independently implement a sub-feature - this keeps your context clean. "
                + "Instructions MUST be self-contained (the sub-agent cannot see your "
                + "conversation history). You can call multiple `task` tools in one turn "
                + "for parallel execution. "
                + "subagent_type: `explore` (read-only, for search/analysis) "
                + "or `general` (full tools, for implementation).";
        }

        return prompt;
    }

    private String buildSystemPrompt() {
        return PromptAssembly.assemble(
            L1_KERNEL,
            l2WorkspaceRules,
            skillCatalog,
            activeSkills,
            buildL4ModePrompt(),
            l5MemoryIndex);
    }

    private void rebuildSystemPrompt() {
        if (!sessionHistory.isEmpty() && sessionHistory.get(0).role() == Role.SYSTEM) {
            sessionHistory.set(0, Message.system(buildSystemPrompt()));
        }
    }

    private static ToolDefinition buildTaskToolDefinition() {
        return new ToolDefinition(
            TASK_TOOL_NAME,
            "Delegate a self-contained subtask to a sub-agent with isolated context. "
                + "Use this to search many files, analyze multiple modules, or independently "
                + "implement a sub-feature while keeping your main context clean. "
                + "Instructions must be self-contained and specific. "
                + "Use subagent_type `explore` (read-only) for search/analysis, "
                + "`general` for implementation tasks.",
            """
            {
              "type": "object",
              "properties": {
                "instruction": {
                  "type": "string",
                  "description": "Clear, self-contained instruction for the sub-agent. Include ALL context needed - the sub-agent cannot see your conversation history."
                },
                "subagent_type": {
                  "type": "string",
                  "enum": ["explore", "general"],
                  "description": "explore = read-only tools for search/analysis. general = full tools for implementation."
                }
              },
              "required": ["instruction"]
            }"""
        );
    }

    private static final int MAX_TURNS = 50;
    private static final int DEAD_LOOP_THRESHOLD = 3;
    private static final int PROGRESS_REMINDER_INTERVAL = 10;
    private static ToolDefinition buildSessionContextToolDefinition() {
        return new ToolDefinition(
            SESSION_CONTEXT_TOOL_NAME,
            "Search past conversation sessions by keyword. "
                + "Use this when the user references a previous session "
                + "(e.g. '继续昨天的重构', '像上次一样', 'as we discussed earlier'). "
                + "Returns matching session summaries with IDs for loading.",
            """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "Search keywords to find relevant past sessions"
                }
              },
              "required": ["query"]
            }"""
        );
    }

    private ToolDefinition buildSkillLoadToolDef() {
        List<String> names = skillCatalog.isEmpty()
            ? List.of() : new ArrayList<>(skillCatalog.entries().keySet());
        String enumJson = names.stream()
            .map(n -> "\"" + n + "\"").collect(java.util.stream.Collectors.joining(", "));
        return new ToolDefinition(
            SKILL_LOAD_TOOL_NAME,
            "Load a skill's full prompt into the current session. "
                + "Use this when a task would benefit from specialized domain knowledge. "
                + "The skill's SKILL.md content will be injected into the system prompt. "
                + "Available skills are listed in the system prompt above.",
            """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "The skill name to load",
                  "enum": [%s]
                }
              },
              "required": ["name"]
            }""".replace("%s", enumJson)
        );
    }

    private static ToolDefinition buildSkillUnloadToolDef() {
        return new ToolDefinition(
            SKILL_UNLOAD_TOOL_NAME,
            "Unload a previously loaded skill to free context space. "
                + "Only affects the current session — the skill can be reloaded later.",
            """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "The skill name to unload"
                }
              },
              "required": ["name"]
            }"""
        );
    }

    private static ToolDefinition buildRememberToolDef() {
        return new ToolDefinition(
            REMEMBER_TOOL_NAME,
            "Store a key-value pair in session working memory. "
                + "Use this to record intermediate findings (file paths, design decisions, "
                + "error patterns) that might be compressed away in later turns. "
                + "Stored values are automatically injected into context each turn. "
                + "Cleared on session exit.",
            """
            {
              "type": "object",
              "properties": {
                "key": {
                  "type": "string",
                  "description": "Short key (kebab-case, e.g. \\"auth-flow\\", \\"db-schema\\")"
                },
                "value": {
                  "type": "string",
                  "description": "The information to persist. Keep concise (<500 chars)."
                }
              },
              "required": ["key", "value"]
            }"""
        );
    }

    private static ToolDefinition buildMemorySaveToolDef() {
        return new ToolDefinition(
            MEMORY_SAVE_TOOL_NAME,
            "Save a memory entry to persistent storage. "
                + "**MUST call this when the user explicitly asks you to remember something.** "
                + "Trigger patterns: \"记一下\" / \"记住\" / \"别忘了\" / \"remember this\" / "
                + "\"keep in mind\" / \"save this\" / \"make a note\". "
                + "Also use proactively for: user preferences, project conventions, "
                + "important decisions, feedback about your approach, "
                + "and non-obvious context that future conversations will need. "
                + "Infer name, description, type, and content from the conversation.",
            """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "kebab-case identifier (e.g. \\"coding-style\\", \\"short-replies\\")"
                },
                "description": {
                  "type": "string",
                  "description": "One-line summary under 120 chars"
                },
                "type": {
                  "type": "string",
                  "enum": ["user", "feedback", "project", "reference"],
                  "description": "Memory type: user=preferences/role/knowledge, feedback=corrections about approach, project=ongoing work/goals, reference=external pointers"
                },
                "content": {
                  "type": "string",
                  "description": "Full memory text in Markdown"
                }
              },
              "required": ["name", "description", "type", "content"]
            }"""
        );
    }

    private static final List<ToolDefinition> EMPTY_TOOLS = Collections.emptyList();

    // SubAgent 常量
    private static final int SUB_MAX_TURNS_EXPLORE = 15;
    private static final int SUB_MAX_TURNS_GENERAL = 25;
    private static final int SUB_PROGRESS_INTERVAL = 7;
    private static final String TASK_TOOL_NAME = "task";
    private static final String SESSION_CONTEXT_TOOL_NAME = "session_context";
    private static final String SKILL_LOAD_TOOL_NAME = "skill_load";
    private static final String SKILL_UNLOAD_TOOL_NAME = "skill_unload";
    private static final String MEMORY_SAVE_TOOL_NAME = "memory_save";
    private static final String REMEMBER_TOOL_NAME = "remember";

    private final LLMProvider provider;
    private final Registry registry;
    private final String workDir;
    private final int contextWindow;
    private final Tokenizer tokenizer;
    private final ContextManager contextManager;
    private final List<Message> sessionHistory = new ArrayList<>();
    private final List<String> recentCallSignatures = new ArrayList<>();
    private volatile ThinkingMode thinkingMode;
    private volatile ExecutionMode executionMode = ExecutionMode.REACT;
    private volatile PlanExecutor planExecutor;
    private volatile Predicate<ExecutionPlan> onPlanReady;
    private volatile PermissionMode permissionMode = PermissionMode.AUTO;
    private volatile Runnable onThinkingBegin;
    private final List<Consumer<String>> onReasoningListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> onThinkingTokenListeners = new CopyOnWriteArrayList<>();
    private volatile ApprovalHandler approvalHandler;
    private final Set<String> autoApprovedTools = ConcurrentHashMap.newKeySet();
    private volatile boolean interrupted;
    volatile int lastRunTurns;
    volatile boolean enableSubAgents = true;
    private final List<Consumer<String>> onTokenListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final List<Consumer<SubAgentSpawnEvent>> onSubAgentSpawnListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SubAgentCompleteEvent>> onSubAgentCompleteListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<AgentStateEvent>> onStateChangeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ToolStartEvent>> onToolStartListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ToolEndEvent>> onToolEndListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<RunEvent>> onRunEventListeners = new CopyOnWriteArrayList<>();
    private volatile String currentRunId;
    private volatile int currentTurnNumber;
    private final ToolCallExecutor toolCallExecutor;
    private final InternalToolRouter internalToolRouter = new InternalToolRouter();
    private volatile SessionService sessionService;
    volatile MaskedContext lastMaskedContext;

    // Compaction status tracking
    private record CompactionStatus(int beforeMsgs, int afterMsgs,
        int beforeTokens, int afterTokens, int mapBatches, boolean usedReduce) {}
    private volatile CompactionStatus lastCompaction;

    // Phase 1-2: 预算管理
    private final ContextBudgetPolicy budgetPolicy;
    private final ContextBudgetAnalyzer budgetAnalyzer;
    private volatile ContextBudgetReport lastBudgetReport;

    // Phase 3b: ephemeral 上下文容器
    private final EphemeralContext ephemeralContext = new EphemeralContext();

    // V3.6 工作内存：会话级键值存储，clearSession 时清空
    private final Map<String, String> workingMemory = new ConcurrentHashMap<>();

    // V3.7 子目标跟踪
    private List<String> previousTodoContents = List.of();
    private int turnsWithoutTodoProgress = 0;
    private static final int TODO_STALL_THRESHOLD = 3;

    // L3 自动记忆提取
    private int turnsSinceLastExtraction = 0;
    private int messagesAtLastExtraction = 0;
    private static final int EXTRACTION_COOLDOWN = 5;
    private static final int MIN_NEW_MESSAGES_FOR_EXTRACTION = 10;
    private boolean sessionAutoSaved = false;

    public AgentEngine(LLMProvider provider, Registry registry, String workDir) {
        this(provider, registry, workDir, ThinkingMode.OFF, null, null, null);
    }

    public AgentEngine(LLMProvider provider, Registry registry, String workDir, ThinkingMode thinkingMode) {
        this(provider, registry, workDir, thinkingMode, null, null, null);
    }

    public AgentEngine(LLMProvider provider, Registry registry, String workDir,
                       ThinkingMode thinkingMode, Consumer<String> onReasoning) {
        this(provider, registry, workDir, thinkingMode, onReasoning, null, null);
    }

    public AgentEngine(LLMProvider provider, Registry registry, String workDir,
                       ThinkingMode thinkingMode, Consumer<String> onReasoning,
                       Consumer<String> onToken) {
        this(provider, registry, workDir, thinkingMode, onReasoning, onToken, null);
    }

    public AgentEngine(LLMProvider provider, Registry registry, String workDir,
                       ThinkingMode thinkingMode, Consumer<String> onReasoning,
                       Consumer<String> onToken, String memoryIndex) {
        this.provider = provider;
        this.registry = registry;
        this.workDir = workDir;
        this.contextWindow = provider != null ? provider.getContextWindow() : 128_000;
        this.tokenizer = TokenizerFactory.create(
            provider != null ? provider.getEncoding() : "cl100k_base");
        this.contextManager = new LadderedCompactor(this::summarizeForCompact, tokenizer);
        this.budgetPolicy = ContextBudgetPolicy.of(contextWindow);
        this.budgetAnalyzer = new ContextBudgetAnalyzer(tokenizer, budgetPolicy);
        this.thinkingMode = thinkingMode;
        if (onReasoning != null) onReasoningListeners.add(onReasoning);
        if (onToken != null) onTokenListeners.add(onToken);
        this.l5MemoryIndex = memoryIndex != null ? memoryIndex : "";
        this.toolCallExecutor = new ToolCallExecutor(registry);
        registerInternalTools();
    }

    /** 将 engine-internal tools 注册到 InternalToolRouter */
    private void registerInternalTools() {
        internalToolRouter.register(TASK_TOOL_NAME, (req, ctx) -> {
            String output = spawnSubAgent(new com.clawkit.tools.schema.ToolCall(
                req.toolCallId(), req.toolName(), req.arguments()));
            return com.clawkit.tools.ToolExecutionResult.success(
                req.toolCallId(), req.toolName(), output, 0,
                com.clawkit.tools.ToolMetadata.conservative(TASK_TOOL_NAME));
        });
        internalToolRouter.register(SESSION_CONTEXT_TOOL_NAME, (req, ctx) -> {
            String output = searchSessionContext(new com.clawkit.tools.schema.ToolCall(
                req.toolCallId(), req.toolName(), req.arguments()));
            return com.clawkit.tools.ToolExecutionResult.success(
                req.toolCallId(), req.toolName(), output, 0,
                new com.clawkit.tools.ToolMetadata(SESSION_CONTEXT_TOOL_NAME, "", null,
                    true, com.clawkit.tools.ToolRiskLevel.LOW, false, false, java.util.Set.of()));
        });
        internalToolRouter.register(SKILL_LOAD_TOOL_NAME, (req, ctx) -> {
            String output = handleSkillLoad(new com.clawkit.tools.schema.ToolCall(
                req.toolCallId(), req.toolName(), req.arguments()));
            return com.clawkit.tools.ToolExecutionResult.success(
                req.toolCallId(), req.toolName(), output, 0,
                new com.clawkit.tools.ToolMetadata(SKILL_LOAD_TOOL_NAME, "", null,
                    true, com.clawkit.tools.ToolRiskLevel.LOW, false, false, java.util.Set.of()));
        });
        internalToolRouter.register(SKILL_UNLOAD_TOOL_NAME, (req, ctx) -> {
            String output = handleSkillUnload(new com.clawkit.tools.schema.ToolCall(
                req.toolCallId(), req.toolName(), req.arguments()));
            return com.clawkit.tools.ToolExecutionResult.success(
                req.toolCallId(), req.toolName(), output, 0,
                new com.clawkit.tools.ToolMetadata(SKILL_UNLOAD_TOOL_NAME, "", null,
                    true, com.clawkit.tools.ToolRiskLevel.LOW, false, false, java.util.Set.of()));
        });
        internalToolRouter.register(MEMORY_SAVE_TOOL_NAME, (req, ctx) -> {
            String output = handleMemorySave(new com.clawkit.tools.schema.ToolCall(
                req.toolCallId(), req.toolName(), req.arguments()));
            return com.clawkit.tools.ToolExecutionResult.success(
                req.toolCallId(), req.toolName(), output, 0,
                new com.clawkit.tools.ToolMetadata(MEMORY_SAVE_TOOL_NAME, "", null,
                    false, com.clawkit.tools.ToolRiskLevel.MEDIUM, true, true, java.util.Set.of()));
        });
        internalToolRouter.register(REMEMBER_TOOL_NAME, (req, ctx) -> {
            String output = handleRemember(new com.clawkit.tools.schema.ToolCall(
                req.toolCallId(), req.toolName(), req.arguments()));
            return com.clawkit.tools.ToolExecutionResult.success(
                req.toolCallId(), req.toolName(), output, 0,
                new com.clawkit.tools.ToolMetadata(REMEMBER_TOOL_NAME, "", null,
                    false, com.clawkit.tools.ToolRiskLevel.LOW, false, false, java.util.Set.of()));
        });
    }

    @Override
    public String run(String userPrompt) {
        log.info("[Engine] 引擎启动，锁定工作区: {}, 思考模式: {}, 执行模式: {}",
            workDir, thinkingMode, executionMode);

        if (executionMode == ExecutionMode.PLAN_EXECUTE) {
            return runPlanExecute(userPrompt);
        }

        String currentRunId = generateRunId();
        this.currentRunId = currentRunId;
        Instant runStartTime = Instant.now();
        fireRunEvent(new RunEvent.RunStarted(
            currentRunId, userPrompt, runStartTime,
            workDir.toString(), provider.toString(),
            permissionMode.name(), thinkingMode.name(), executionMode.name()));

        if (sessionHistory.isEmpty()) {
            sessionHistory.add(Message.system(buildSystemPrompt()));
        }
        // Phase 3b: 工作区状态注入 → workspaceContext（ephemeral）
        sniffWorkspaceState();
        sessionHistory.add(Message.user(userPrompt));
        // Phase 3b: 相关历史会话注入 → memoryContext（ephemeral）
        injectRelatedSessions();
        // Phase 3a: 打破别名 — contextHistory 是独立副本
        List<Message> contextHistory = new ArrayList<>(sessionHistory);

        int turnCount = 0;
        int totalToolCalls = 0;
        int totalToolFailures = 0;
        int totalCompactCount = 0;
        lastRunTurns = 0;
        fireState(AgentState.IDLE, 0);

        while (true) {
            if (interrupted) {
                interrupted = false;
                fireState(AgentState.INTERRUPTED, turnCount);
                log.info("[Engine] 被用户中断，退出循环");
                fireRunEvent(new RunEvent.RunCompleted(
                    currentRunId, Instant.now(), RunStatus.INTERRUPTED,
                    null, null, turnCount, totalToolCalls, totalToolFailures, totalCompactCount));
                return "[A-001] 已被用户中断。";
            }

            turnCount++;
            this.currentTurnNumber = turnCount;
            log.info("========== [Turn {}] 开始 ==========", turnCount);
            fireRunEvent(new RunEvent.TurnStarted(currentRunId, turnCount, Instant.now()));

            // === 运行时事件注入 ===

            // 第1层: 死循环检测 — 连续3轮相同工具+相同参数
            if (recentCallSignatures.size() >= DEAD_LOOP_THRESHOLD) {
                String last = recentCallSignatures.get(recentCallSignatures.size() - 1);
                boolean deadLoop = true;
                for (int i = recentCallSignatures.size() - DEAD_LOOP_THRESHOLD;
                     i < recentCallSignatures.size(); i++) {
                    if (!recentCallSignatures.get(i).equals(last)) {
                        deadLoop = false;
                        break;
                    }
                }
                if (deadLoop) {
                    String warning = "[Runtime] 检测到连续 " + DEAD_LOOP_THRESHOLD
                        + " 次相同操作 (" + last + ")，可能陷入循环。请换一种方法或向用户确认。";
                    ephemeralContext.runtime().add(Message.system(warning));
                    log.warn("[Engine] 死循环检测触发: {}", last);
                    recentCallSignatures.clear();
                }
            }

            // 第2层: 进度提醒 — 每10轮注入
            if (turnCount > 1 && turnCount % PROGRESS_REMINDER_INTERVAL == 0) {
                String reminder = "[Runtime] 当前已执行 " + turnCount
                    + " 轮。请简要评估推进进度，如任务复杂可考虑拆分为子步骤。";
                ephemeralContext.runtime().add(Message.system(reminder));
                log.info("[Engine] 进度提醒注入: 第 {} 轮", turnCount);
            }

            // 第3层: 安全硬上限 — 50轮兜底
            if (turnCount > MAX_TURNS) {
                log.warn("[Engine] 达到安全硬上限 ({}), 强制退出", MAX_TURNS);
                fireRunEvent(new RunEvent.RunCompleted(
                    currentRunId, Instant.now(), RunStatus.HARD_LIMIT,
                    "A-001", "达到最大迭代轮次 (" + MAX_TURNS + ")", turnCount, totalToolCalls, totalToolFailures, totalCompactCount));
                return "[A-001] 达到最大迭代轮次 (" + MAX_TURNS + ")，任务可能过于复杂，请拆分为子任务";
            }

            // 第4层: 子目标暂停检测 — todo_write 连续 N 轮无进展
            if (turnsWithoutTodoProgress >= TODO_STALL_THRESHOLD) {
                String stall = "[Runtime] 任务清单连续 " + TODO_STALL_THRESHOLD
                    + " 轮无进展。请检查当前方法是否有效，考虑换策略或向用户确认。";
                ephemeralContext.runtime().add(Message.system(stall));
                log.warn("[Engine] 子目标暂停检测触发");
                turnsWithoutTodoProgress = 0;
            }

            // 工作内存注入（V3.6）
            if (!workingMemory.isEmpty()) {
                StringBuilder wm = new StringBuilder("[Working Memory]\n");
                workingMemory.forEach((k, v) ->
                    wm.append("- ").append(k).append(": ").append(v).append("\n"));
                ephemeralContext.memory().add(Message.system(wm.toString().stripTrailing()));
            }

            // L3 自动记忆提取（条件触发：token>60% + 冷却>=5轮 + 新增>=10条消息）
            extractMemories(contextHistory, turnCount);

            // Phase 3b: 组装 ModelContext（ephemeral 容器 + sessionHistory）
            var modelContext = assembleModelContext();
            // always-on 规则（始终执行，不依赖预算）
            modelContext = contextManager.applyAlwaysOnRules(modelContext);

            // Phase 2: 上下文掩码（>12轮时启用角色+时效分层掩码）
            List<TurnGroup> evictedGroups = List.of();
            if (MessageMasker.shouldMask(turnCount)) {
                var masked = MessageMasker.mask(modelContext, turnCount);
                modelContext = masked.messages();
                evictedGroups = masked.evictedTurnGroups();
                lastMaskedContext = masked;
                log.info("[Engine] 上下文掩码: T0={}, T1={}, T2={}, T3={} (evicted:{} groups)",
                    masked.tier0Count(), masked.tier1Count(),
                    masked.tier2Count(), masked.tier3Count(),
                    evictedGroups.size());
            }

            // Phase 2: 预算分析
            int toolDefTokens = estimateToolTokens();
            var report = budgetAnalyzer.analyze(modelContext, toolDefTokens, Map.of());
            this.lastBudgetReport = report;

            // Phase 2: 根据预算状态决策
            // 核心语义：
            //   - targetTokens (70%) 是 compact 的目标线，不是保证线
            //   - compact 是一趟过算法（always-on -> pressure -> L3），压到哪算哪
            //   - compact 后一律重分析，根据实际结果决策
            switch (report.status()) {
                case OK -> { /* 预算正常，不压缩 */ }
                case WARN -> { /* 预警，不强制压缩 */ }
                case COMPACT_REQUIRED, HARD_LIMIT -> {
                    // 超过 85%（或 95%），强制 compact
                    totalCompactCount++;
                    fireRunEvent(new RunEvent.CompactTriggered(
                        currentRunId, turnCount, Instant.now()));
                    var result = contextManager.compact(modelContext,
                        budgetPolicy.targetTokens(), evictedGroups);
                    CompactionResult cr = (CompactionResult) result;
                    this.lastCompaction = new CompactionStatus(
                        modelContext.size(), cr.messages().size(),
                        report.totalTokens(), cr.afterReport() != null
                            ? cr.afterReport().totalTokens() : 0,
                        evictedGroups.size(),
                        cr.afterReport() != null
                            && cr.afterReport().totalTokens() < report.totalTokens());

                    // compact 后重分析
                    var postReport = budgetAnalyzer.analyze(
                        cr.messages(), toolDefTokens, Map.of());
                    this.lastBudgetReport = postReport;

                    // Compact completed metrics
                    var beforeSections = new java.util.LinkedHashMap<String, Integer>();
                    report.sections().forEach((k, v) -> beforeSections.put(k.name(), v));
                    var afterSections = new java.util.LinkedHashMap<String, Integer>();
                    postReport.sections().forEach((k, v) -> afterSections.put(k.name(), v));
                    fireRunEvent(new RunEvent.CompactCompleted(currentRunId,
                        new com.clawkit.observability.model.CompactMetrics(
                            currentRunId, turnCount,
                            modelContext.size(), cr.messages().size(),
                            report.totalTokens(), postReport.totalTokens(),
                            report.status().name(), postReport.status().name(),
                            beforeSections, afterSections,
                            evictedGroups.size(), List.of())));

                    if (postReport.status() == ContextBudgetReport.BudgetStatus.HARD_LIMIT) {
                        log.warn("[Engine] compact 后仍超 95% 硬限制");
                        fireRunEvent(new RunEvent.RunCompleted(
                            currentRunId, Instant.now(), RunStatus.COMPACT_FAILED,
                            "A-001", "compact 后仍超 95% 硬限制", turnCount, totalToolCalls, totalToolFailures, totalCompactCount));
                        return String.format(
                            "上下文过大（%d / %d tokens，%.0f%%），compact 后仍超 95%% 硬限制。"
                            + "请执行 /compact 或 /session new 开启新会话。",
                            postReport.totalTokens(), budgetPolicy.contextWindow(),
                            postReport.percentage() * 100);
                    }

                    modelContext = cr.messages();
                    // Phase 3c: 只把干净消息写回 sessionHistory
                    sessionHistory.clear();
                    sessionHistory.addAll(filterPersistable(modelContext));
                    int delta = report.totalTokens()
                        - (postReport.totalTokens() > 0 ? postReport.totalTokens() : report.totalTokens());
                    log.info("[Engine] compact: {} → {} tokens (-{}%)",
                        report.totalTokens(), postReport.totalTokens(),
                        report.totalTokens() > 0 ? (int)(100.0 * delta / report.totalTokens()) : 0);
                }
            }
            contextHistory = modelContext;

            // === 两阶段慢思考：每轮先剥离工具，强制推理规划 ===
            // Phase 1 的思考内容作为 ephemeral context 注入 Phase 2 但不持久化，
            // 避免 token 浪费和内部 Trace 泄露。
            Message thinkingMsg = null;
            if (thinkingMode == ThinkingMode.TWO_STAGE && turnCount == 1) {
                fireState(AgentState.PLANNING, turnCount);
                log.info("[Engine] 慢思考阶段1: 剥离工具，强制推理规划...");
                try {
                    if (onThinkingBegin != null) onThinkingBegin.run();

                    // Phase 1 注入规划指令：防止 LLM 在无工具阶段直接回答
                    List<Message> phase1Context = new ArrayList<>(contextHistory);
                    phase1Context.add(Message.system(
                        "[Planning Phase] 你现在处于规划阶段，不能调用任何工具。"
                        + "严格按以下格式回复（2-4句话即可）："
                        + "1) 用户想要什么？"
                        + "2) 你打算用什么工具、按什么顺序？"
                        + "禁止事项：不要生成 <tool_calls> XML、不要直接回答用户问题、"
                        + "不要写代码、不要超过6句话。"));

                    // Phase 1 流式输出：onThinkingToken 优先，onToken 兜底
                    List<Consumer<String>> phase1Listeners = !onThinkingTokenListeners.isEmpty()
                        ? onThinkingTokenListeners : onTokenListeners;
                    StringBuilder sb = new StringBuilder();
                    Message rawThinking;
                    if (!phase1Listeners.isEmpty()) {
                        rawThinking = provider.generateStream(phase1Context, EMPTY_TOOLS, token -> {
                            sb.append(token);
                            phase1Listeners.forEach(l -> l.accept(token));
                        });
                    } else {
                        rawThinking = provider.generate(phase1Context, EMPTY_TOOLS);
                        sb.append(rawThinking.content() != null ? rawThinking.content() : "");
                    }

                    // 清洗伪 XML tool_call（DeepSeek 可能在无工具阶段幻想出 tool_call 文本）
                    String cleanContent = stripFakeToolCalls(sb.toString());
                    thinkingMsg = new Message(rawThinking.role(), cleanContent,
                        rawThinking.toolCalls(), rawThinking.toolCallId());

                    if (!cleanContent.isEmpty()) {
                        log.debug("[推理] {}", cleanContent);
                    }
                    if (!onReasoningListeners.isEmpty()) {
                        onReasoningListeners.forEach(l -> l.accept(cleanContent));
                    }
                    log.info("[Engine] 慢思考阶段1 完成");
                } catch (LLMException e) {
                    fireState(AgentState.ERROR, turnCount, Map.of("error", e.getMessage()));
                    log.error("[Engine] 慢思考阶段1 失败: {}", e.getMessage());
                    fireRunEvent(new RunEvent.RunCompleted(
                        currentRunId, Instant.now(), RunStatus.PLANNING_ERROR,
                        "A-002", e.getMessage(), turnCount, totalToolCalls, totalToolFailures, totalCompactCount));
                    return "[A-002] 慢思考规划阶段 LLM 调用失败: " + e.getMessage();
                }
            }

            // === 第二阶段 / 普通模式：带工具推理 ===
            List<ToolDefinition> availableTools;
            if (permissionMode == PermissionMode.PLAN) {
                availableTools = registry.getAvailableTools().stream()
                    .filter(t -> registry.isReadOnly(t.name()))
                    .toList();
                log.info("[Engine] PLAN 模式: 仅暴露 {} 个读工具", availableTools.size());
            } else {
                availableTools = new ArrayList<>(registry.getAvailableTools());
                if (enableSubAgents) {
                    availableTools.add(buildTaskToolDefinition());
                }
            }
            // session_context 在所有模式都可用（只读操作，无副作用）
            if (sessionService != null) {
                availableTools = new ArrayList<>(availableTools);
                availableTools.add(buildSessionContextToolDefinition());
            }
            // skill_load / skill_unload 在所有模式都可用（读操作）
            if (skillLoader != null && !skillCatalog.isEmpty()) {
                availableTools = new ArrayList<>(availableTools);
                availableTools.add(buildSkillLoadToolDef());
                availableTools.add(buildSkillUnloadToolDef());
            }
            // memory_save 在 ASK/AUTO 模式可用（写操作）
            if (diskMemoryService != null && permissionMode != PermissionMode.PLAN) {
                availableTools = new ArrayList<>(availableTools);
                availableTools.add(buildMemorySaveToolDef());
            }
            // remember 在 ASK/AUTO 模式可用（会话内写操作）
            if (permissionMode != PermissionMode.PLAN) {
                availableTools = new ArrayList<>(availableTools);
                availableTools.add(buildRememberToolDef());
            }
            if (thinkingMode == ThinkingMode.TWO_STAGE) {
                log.info("[Engine] 慢思考阶段2: 带工具执行 ({})...", availableTools.size());
            } else {
                log.info("[Engine] 正在思考 (Reasoning)...");
            }

            // 构建 Phase 2 上下文：thinkingMsg 作为 ephemeral 注入，不持久化
            List<Message> phase2Context = contextHistory;
            if (thinkingMsg != null) {
                phase2Context = new ArrayList<>(contextHistory);
                phase2Context.add(thinkingMsg);
            }

            fireState(AgentState.REASONING, turnCount);
            final long providerStartMs = System.currentTimeMillis();
            final Message responseMsg;
            try {
                if (!onTokenListeners.isEmpty()) {
                    responseMsg = provider.generateStream(phase2Context, availableTools,
                        token -> onTokenListeners.forEach(l -> l.accept(token)));
                } else {
                    responseMsg = provider.generate(phase2Context, availableTools);
                }
            } catch (LLMException e) {
                fireState(AgentState.ERROR, turnCount, Map.of("error", e.getMessage()));
                log.error("[Engine] LLM 调用失败 (A-002): {}", e.getMessage());
                fireRunEvent(new RunEvent.RunCompleted(
                    currentRunId, Instant.now(), RunStatus.LLM_ERROR,
                    "A-002", e.getMessage(), turnCount, totalToolCalls, totalToolFailures, totalCompactCount));
                return "[A-002] LLM 调用失败: " + e.getMessage();
            }
            long providerDurationMs = System.currentTimeMillis() - providerStartMs;
            contextHistory.add(responseMsg);
            int toolCallCount = responseMsg.toolCalls() != null ? responseMsg.toolCalls().size() : 0;

            // Fire provider + turn events
            fireRunEvent(new RunEvent.ProviderCallCompleted(currentRunId, turnCount,
                new ProviderCallMetrics(currentRunId, turnCount, "phase2",
                    !onTokenListeners.isEmpty(), 0, 0, true, providerDurationMs, 0,
                    false, null, null)));
            fireRunEvent(new RunEvent.TurnCompleted(currentRunId,
                new TurnMetrics(currentRunId, turnCount, "-",
                    0, 0, true, providerDurationMs, toolCallCount,
                    responseMsg.content() != null && !responseMsg.content().isEmpty(),
                    false, null, null)));

            if (responseMsg.content() != null && !responseMsg.content().isEmpty()) {
                log.debug("Model response: {} chars", responseMsg.content().length());
            }

            // 退出条件：没有工具调用（V3.7 增强：检测所有 todo 是否完成）
            if (responseMsg.toolCalls() == null || responseMsg.toolCalls().isEmpty()) {
                fireState(AgentState.REPLYING, turnCount);
                if (allTodosCompleted()) {
                    log.info("[Engine] 所有子目标完成，任务结束。");
                } else {
                    log.info("[Engine] LLM 未调用工具，退出循环。");
                }
                lastRunTurns = turnCount;
                fireRunEvent(new RunEvent.RunCompleted(
                    currentRunId, Instant.now(), RunStatus.COMPLETED,
                    null, null, turnCount, totalToolCalls, totalToolFailures, totalCompactCount));
                return responseMsg.content() != null ? responseMsg.content() : "";
            }

            // Action + Observation
            List<ToolCall> calls = responseMsg.toolCalls();
            {
                Map<String, Object> execMeta = Map.of(
                    "toolNames", calls.stream().map(ToolCall::name).toList(),
                    "toolCount", calls.size()
                );
                fireState(AgentState.EXECUTING, turnCount, execMeta);
            }
            log.info("[Engine] 模型请求调用 {} 个工具, {} 模式...",
                calls.size(), allReadOnly(calls) ? "并行" : "串行");

            // 通过 ToolCallExecutor 统一执行（替代旧的 executeParallel/executeSequential）
            if (calls.stream().allMatch(c -> TASK_TOOL_NAME.equals(c.name()))) {
                log.info("[Engine] SubAgent 并行: {} 个子任务", calls.size());
                totalToolCalls += calls.size();
                executeSubAgentsParallel(calls, contextHistory);
            } else {
                totalToolCalls += calls.size();
                var execCtx = new ToolExecutionContext(
                    currentRunId, turnCount, permissionMode, approvalHandler,
                    this::fireRunEvent, internalToolRouter, autoApprovedTools);
                var batchResult = toolCallExecutor.executeBatch(calls, execCtx);
                for (Message msg : batchResult.toolResultMessages()) {
                    contextHistory.add(msg);
                    if ("todo_write".equals(
                        calls.stream().filter(c -> c.id().equals(msg.toolCallId()))
                            .findFirst().map(ToolCall::name).orElse(""))) {
                        syncTodoToWorkspace(
                            calls.stream().filter(c -> c.id().equals(msg.toolCallId()))
                                .findFirst().map(ToolCall::arguments).orElse(null));
                    }
                }
            }

            // 记录调用签名用于死循环检测
            for (ToolCall call : calls) {
                String sig = call.name() + ":" + call.arguments().toString();
                recentCallSignatures.add(sig);
            }
            while (recentCallSignatures.size() > 10) {
                recentCallSignatures.remove(0);
            }
        }
    }

    private boolean allReadOnly(List<ToolCall> calls) {
        return calls.stream().allMatch(c -> registry.isReadOnly(c.name()));
    }

    private void executeParallel(List<ToolCall> calls, List<Message> contextHistory) {
        int n = calls.size();
        ToolResult[] results = new ToolResult[n];
        CountDownLatch latch = new CountDownLatch(n);

        for (int i = 0; i < n; i++) {
            final int idx = i;
            final ToolCall call = calls.get(i);
            log.info("  -> [VThread-{}] 并行执行: {}", idx, call.name());
            fireToolStart(call);
            fireRunEvent(new RunEvent.ToolInvoked(
                currentRunId, currentTurnNumber, call.id(), call.name(),
                buildArgSummary(call), registry.isReadOnly(call.name())));
            Thread.ofVirtual().start(() -> {
                try {
                    results[idx] = registry.execute(call);
                    ToolResult r = results[idx];
                    fireToolEnd(call, r);
                    fireRunEvent(new RunEvent.ToolCompleted(currentRunId, currentTurnNumber,
                        new ToolCallMetrics(currentRunId, currentTurnNumber,
                            call.id(), call.name(), buildArgSummary(call),
                            registry.isReadOnly(call.name()), false, false, null,
                            !r.isError(), 0, r.output().length(), false,
                            r.isError() ? "TOOL_ERROR" : null,
                            r.isError() ? r.output().substring(0, Math.min(80, r.output().length())) : null)));
                    if (r.isError()) {
                        log.info("  -> [VThread-{}] 执行报错: {}", idx, r.output());
                    } else {
                        log.info("  -> [VThread-{}] 执行成功 (返回 {} 字节)", idx, r.output().length());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Engine] 并行执行被中断");
        }

        for (int i = 0; i < results.length; i++) {
            ToolResult result = results[i];
            contextHistory.add(Message.toolResult(result.toolCallId(), result.output()));
            if (!result.isError() && "todo_write".equals(calls.get(i).name())) {
                syncTodoToWorkspace(calls.get(i).arguments());
                trackTodoProgress(calls.get(i).arguments());
            }
        }
    }

    private void executeSequential(List<ToolCall> calls, List<Message> contextHistory) {
        for (ToolCall call : calls) {
            // task 工具由引擎直接处理，不走 Registry
            if (TASK_TOOL_NAME.equals(call.name())) {
                log.info("  -> [SubAgent] 派发子任务");
                fireRunEvent(new RunEvent.ToolInvoked(
                    currentRunId, currentTurnNumber, call.id(), call.name(), "", false));
                String result = spawnSubAgent(call);
                contextHistory.add(Message.toolResult(call.id(), result));
                fireRunEvent(new RunEvent.ToolCompleted(currentRunId, currentTurnNumber,
                    internalToolMetrics(call, true, result)));
                continue;
            }
            // session_context 工具由引擎直接处理
            if (SESSION_CONTEXT_TOOL_NAME.equals(call.name())) {
                log.info("  -> [Session] 搜索历史会话");
                fireRunEvent(new RunEvent.ToolInvoked(
                    currentRunId, currentTurnNumber, call.id(), call.name(), "", true));
                String result = searchSessionContext(call);
                contextHistory.add(Message.toolResult(call.id(), result));
                fireRunEvent(new RunEvent.ToolCompleted(currentRunId, currentTurnNumber,
                    internalToolMetrics(call, true, result)));
                continue;
            }
            // skill_load / skill_unload — engine-internal
            if (SKILL_LOAD_TOOL_NAME.equals(call.name())) {
                log.info("  -> [Skill] 加载技能");
                fireRunEvent(new RunEvent.ToolInvoked(
                    currentRunId, currentTurnNumber, call.id(), call.name(), "", true));
                String result = handleSkillLoad(call);
                contextHistory.add(Message.toolResult(call.id(), result));
                fireRunEvent(new RunEvent.ToolCompleted(currentRunId, currentTurnNumber,
                    internalToolMetrics(call, true, result)));
                continue;
            }
            if (SKILL_UNLOAD_TOOL_NAME.equals(call.name())) {
                log.info("  -> [Skill] 卸载技能");
                fireRunEvent(new RunEvent.ToolInvoked(
                    currentRunId, currentTurnNumber, call.id(), call.name(), "", true));
                String result = handleSkillUnload(call);
                contextHistory.add(Message.toolResult(call.id(), result));
                fireRunEvent(new RunEvent.ToolCompleted(currentRunId, currentTurnNumber,
                    internalToolMetrics(call, true, result)));
                continue;
            }
            // memory_save — engine-internal
            if (MEMORY_SAVE_TOOL_NAME.equals(call.name())) {
                log.info("  -> [Memory] 保存记忆");
                fireRunEvent(new RunEvent.ToolInvoked(
                    currentRunId, currentTurnNumber, call.id(), call.name(), "", false));
                String result = handleMemorySave(call);
                contextHistory.add(Message.toolResult(call.id(), result));
                fireRunEvent(new RunEvent.ToolCompleted(currentRunId, currentTurnNumber,
                    internalToolMetrics(call, false, result)));
                continue;
            }
            // remember — V3.6 工作内存
            if (REMEMBER_TOOL_NAME.equals(call.name())) {
                log.info("  -> [WM] 工作内存写入");
                fireRunEvent(new RunEvent.ToolInvoked(
                    currentRunId, currentTurnNumber, call.id(), call.name(), "", false));
                String result = handleRemember(call);
                contextHistory.add(Message.toolResult(call.id(), result));
                fireRunEvent(new RunEvent.ToolCompleted(currentRunId, currentTurnNumber,
                    internalToolMetrics(call, false, result)));
                continue;
            }
            if (!registry.isReadOnly(call.name())) {
                if (permissionMode == PermissionMode.PLAN) {
                    contextHistory.add(Message.toolResult(call.id(),
                        "Tool '" + call.name() + "' is not available in PLAN mode. "
                        + "Switch to ASK or AUTO mode to execute write operations."));
                    continue;
                }
                if (permissionMode == PermissionMode.ASK && approvalHandler != null) {
                    if (!autoApprovedTools.contains(call.name())) {
                        ApprovalRequest req = ApprovalRequest.from(call, lastAssistantMessage(contextHistory));
                        ApprovalResult decision = approvalHandler.handle(req);
                        switch (decision) {
                            case ApprovalResult.Approve __ -> { /* 执行 */ }
                            case ApprovalResult.ApproveAllSameType a -> {
                                autoApprovedTools.add(a.toolName());
                            }
                            case ApprovalResult.Reject r -> {
                                fireRunEvent(new RunEvent.ApprovalDecision(
                                    currentRunId, currentTurnNumber, call.name(), "REJECT", r.reason()));
                                contextHistory.add(Message.toolResult(call.id(),
                                    "User rejected " + call.name() + ": " + r.reason()));
                                continue;
                            }
                            case ApprovalResult.ModifyParams m -> {
                                fireRunEvent(new RunEvent.ApprovalDecision(
                                    currentRunId, currentTurnNumber, call.name(), "MODIFY", m.guidance()));
                                contextHistory.add(Message.toolResult(call.id(),
                                    "User wants you to modify the parameters. " + m.guidance()
                                    + " Please adjust and call " + call.name() + " again."));
                                continue;
                            }
                        }
                    }
                }
            }
            fireToolStart(call);
            fireRunEvent(new RunEvent.ToolInvoked(
                currentRunId, currentTurnNumber, call.id(), call.name(),
                buildArgSummary(call), registry.isReadOnly(call.name())));
            long toolStartMs = System.currentTimeMillis();
            ToolResult result = registry.execute(call);
            long toolDurationMs = System.currentTimeMillis() - toolStartMs;
            fireToolEnd(call, result);
            fireRunEvent(new RunEvent.ToolCompleted(currentRunId, currentTurnNumber,
                new ToolCallMetrics(currentRunId, currentTurnNumber,
                    call.id(), call.name(), buildArgSummary(call),
                    registry.isReadOnly(call.name()), false, false, null,
                    !result.isError(), toolDurationMs, result.output().length(), false,
                    result.isError() ? "TOOL_ERROR" : null,
                    result.isError() ? result.output().substring(0, Math.min(80, result.output().length())) : null)));
            log.info("  -> 执行工具: {}, 参数: {}", call.name(), call.arguments());
            if (result.isError()) {
                log.info("  -> 工具执行报错: {}", result.output());
            } else {
                log.info("  -> 工具执行成功 (返回 {} 字节)", result.output().length());
                if ("todo_write".equals(call.name())) {
                    syncTodoToWorkspace(call.arguments());
                    trackTodoProgress(call.arguments());
                }
            }
            contextHistory.add(Message.toolResult(call.id(), result.output()));
        }
    }

    private void executeSubAgentsParallel(List<ToolCall> calls, List<Message> contextHistory) {
        int n = calls.size();
        String[] results = new String[n];
        CountDownLatch latch = new CountDownLatch(n);

        for (int i = 0; i < n; i++) {
            final int idx = i;
            final ToolCall call = calls.get(i);
            Thread.ofVirtual().start(() -> {
                try {
                    results[idx] = spawnSubAgent(call);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Engine] SubAgent 并行执行被中断");
        }

        for (int i = 0; i < n; i++) {
            contextHistory.add(Message.toolResult(calls.get(i).id(), results[i]));
        }
    }

    // === SubAgent 派发 ===

    private String spawnSubAgent(ToolCall call) {
        JsonNode args = call.arguments();
        String instruction = args.has("instruction") ? args.get("instruction").asText() : "";
        if (instruction.isBlank()) {
            return "Error: sub-agent instruction is required.";
        }

        String type = args.has("subagent_type") ? args.get("subagent_type").asText() : "general";
        if (!"explore".equals(type) && !"general".equals(type)) {
            type = "general";
        }

        int maxTurns = "explore".equals(type) ? SUB_MAX_TURNS_EXPLORE : SUB_MAX_TURNS_GENERAL;

        // 通知 CLI 层
        SubAgentSpawnEvent spawnEvent = new SubAgentSpawnEvent(instruction, type, maxTurns);
        for (var listener : onSubAgentSpawnListeners) {
            try { listener.accept(spawnEvent); } catch (Exception ignored) {}
        }

        // 构建子 Registry：explore 仅读工具，general 排除 task
        ToolRegistry subRegistry = new ToolRegistry();
        for (ToolDefinition td : registry.getAvailableTools()) {
            if (TASK_TOOL_NAME.equals(td.name())) continue;
            if ("explore".equals(type) && !registry.isReadOnly(td.name())) continue;
            registry.lookup(td.name()).ifPresent(subRegistry::register);
        }
        log.info("[SubAgent] {} 模式, 可用工具: {}", type, subRegistry.count());

        // 创建子引擎：不继承 TWO_STAGE，继承 permissionMode
        AgentEngine subEngine = new AgentEngine(provider, subRegistry, workDir,
            ThinkingMode.OFF, null, null, l5MemoryIndex);
        subEngine.setPermissionMode(permissionMode);
        subEngine.enableSubAgents = false; // 防止递归委派
        if (approvalHandler != null) {
            subEngine.setApprovalHandler(approvalHandler);
        }
        // 子引擎不流式输出（避免干扰主 Agent 显示）
        subEngine.lastRunTurns = 0;

        long startMs = System.currentTimeMillis();
        String result;
        try {
            result = subEngine.run(instruction);
        } catch (Exception e) {
            log.error("[SubAgent] 执行异常: {}", e.getMessage(), e);
            result = "[A-003] SubAgent execution failed: " + e.getMessage();
        }
        long durationMs = System.currentTimeMillis() - startMs;

        int turns = subEngine.lastRunTurns;
        int tokens = subEngine.getEstimatedTokens();

        // 通知 CLI 层完成
        SubAgentCompleteEvent completeEvent = new SubAgentCompleteEvent(result, turns, tokens, durationMs);
        for (var listener : onSubAgentCompleteListeners) {
            try { listener.accept(completeEvent); } catch (Exception ignored) {}
        }

        log.info("[SubAgent] 完成: {} turns, {} tokens, {}ms", turns, tokens, durationMs);
        return result;
    }

    @Override
    public void setThinkingMode(ThinkingMode mode) {
        this.thinkingMode = mode;
        log.info("[Engine] 思考模式切换为: {}", mode);
    }

    @Override
    public void setPermissionMode(PermissionMode mode) {
        this.permissionMode = mode;
        rebuildSystemPrompt();
        log.info("[Engine] 权限模式切换为: {}", mode);
    }

    @Override
    public PermissionMode permissionMode() {
        return permissionMode;
    }

    @Override
    public void interrupt() {
        this.interrupted = true;
        log.info("[Engine] 收到中断信号");
    }

    // ── 对话结束事实提取 ──────────────────────────────────────────

    private void extractFacts() {
        if (diskMemoryService == null || permissionMode == PermissionMode.PLAN) return;
        if (sessionHistory.size() <= 1) return; // 只有系统提示，无实际对话

        log.info("[Engine] 对话结束事实提取: {} 条消息", sessionHistory.size());

        List<Message> extractMsgs = new ArrayList<>();
        extractMsgs.add(Message.system(EXTRACTION_PROMPT));
        extractMsgs.add(Message.user(formatMessagesForExtraction(sessionHistory)));

        try {
            Message result = provider.generate(extractMsgs, EMPTY_TOOLS);
            if (result.content() == null || result.content().isBlank()) return;

            List<MemoryEntry> entries = parseExtractionResult(result.content());
            int saved = 0;
            for (MemoryEntry entry : entries) {
                if (saved >= 5) break;
                diskMemoryService.save(entry);
                saved++;
                log.info("[Engine] extractFacts 已保存: {} [{}]", entry.name(), entry.type());
            }
            if (saved > 0) {
                setMemoryIndex(diskMemoryService.loadIndex());
                log.info("[Engine] extractFacts 保存 {} 条记忆", saved);
            }
        } catch (LLMException e) {
            log.warn("[Engine] extractFacts LLM 失败: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[Engine] extractFacts 失败: {}", e.getMessage());
        }
    }

    @Override
    public void clearSession() {
        extractFacts();
        autoSaveSession();
        sessionHistory.clear();
        // Phase 3b: 清空 ephemeral 容器
        ephemeralContext.clear();
        recentCallSignatures.clear();
        autoApprovedTools.clear();
        workingMemory.clear();
        previousTodoContents = List.of();
        turnsWithoutTodoProgress = 0;
        turnsSinceLastExtraction = 0;
        messagesAtLastExtraction = 0;
        sessionAutoSaved = false;
        interrupted = false;
        log.info("[Engine] 会话已清空 (含工作内存 + 子目标跟踪 + ephemeral 容器)");
    }

    // ── L3: 自动记忆提取 ──────────────────────────────────────────

    private static final String EXTRACTION_PROMPT =
        "你是记忆提取器。从对话中识别值得跨会话保留的信息，让未来的对话能从中受益。\n"
        + "\n"
        + "## 输出格式\n"
        + "\n"
        + "纯 JSON 数组。无可提取内容时返回 []。每次最多 3 条。\n"
        + "\n"
        + "字段说明:\n"
        + "- name: 英文标识符，kebab-case，如 \"user_role\" \"feedback_no_mock_db\"\n"
        + "- description: 一句话摘要，用于 MEMORY.md 索引行，15 字以内\n"
        + "- type: \"user\" | \"feedback\" | \"project\" | \"reference\"\n"
        + "- content: Markdown 正文，150 字以内。feedback 类用 **Why:** + **How to apply:** 结构\n"
        + "\n"
        + "## 记忆类型\n"
        + "\n"
        + "- user — 用户角色、偏好、知识背景、工作习惯\n"
        + "- feedback — 用户对工作方式的纠正或肯定，含原因和适用场景\n"
        + "- project — 项目决策、约束、截止日期、进行中的工作\n"
        + "- reference — 外部资源指针（URL、文档位置、系统名称）\n"
        + "\n"
        + "## 提取标准\n"
        + "\n"
        + "提取:\n"
        + "- 未来对话可能需要参考的信息（偏好、决策、背景）\n"
        + "- 用户明确表达的喜好或习惯\n"
        + "- 无法从代码/git 中推导的上下文\n"
        + "\n"
        + "跳过:\n"
        + "- 临时任务细节（修 bug、改配置、加 import）\n"
        + "- 可从代码文件直接读取的信息（目录结构、类名、方法签名）\n"
        + "- 纯工具操作记录（glob/grep/read 的调用和输出）\n"
        + "- 本次已提取过的重复信息\n"
        + "\n"
        + "## 示例\n"
        + "\n"
        + "✓ 提取:\n"
        + "用户:\"我是数据科学家，主要看日志\"\n"
        + "→ {\"name\":\"user_role\",\"description\":\"用户是数据科学家，关注日志\",\"type\":\"user\",\"content\":\"用户是数据科学家，当前关注可观测性和日志系统。解释代码时优先从数据管道角度切入。\"}\n"
        + "\n"
        + "用户:\"别 mock 数据库，上次 mock 过了但生产炸了\"\n"
        + "→ {\"name\":\"feedback_no_mock_db\",\"description\":\"集成测试禁用数据库 mock\",\"type\":\"feedback\",\"content\":\"集成测试必须连真实数据库，禁止 mock。\\n\\n**Why:** 历史 mock/生产差异导致迁移失败\\n**How to apply:** 写集成测试时直接用真实数据库\"}\n"
        + "\n"
        + "✗ 跳过:\n"
        + "\"帮我读一下 src/main/App.java\" — 临时操作\n"
        + "\"这个函数复杂度 O(n log n)\" — 可从代码推导\n"
        + "\"编译报错: 缺少 import\" — 临时排错";

    private void extractMemories(List<Message> contextHistory, int turnCount) {
        if (diskMemoryService == null || permissionMode == PermissionMode.PLAN) return;

        turnsSinceLastExtraction++;
        int currentMsgCount = contextHistory.size();

        // 触发条件：冷却 >= 5 轮 AND 新增 >= 10 条消息 AND token > 60%
        if (turnsSinceLastExtraction < EXTRACTION_COOLDOWN) return;
        if (currentMsgCount - messagesAtLastExtraction < MIN_NEW_MESSAGES_FOR_EXTRACTION) return;

        int tokens = contextManager.estimateTokens(contextHistory);
        if (tokens < contextWindow * 0.6) return;

        // 裁剪：只取新消息，TOOL 输出截断
        List<Message> newMessages = new ArrayList<>();
        int start = messagesAtLastExtraction;
        for (int i = start; i < contextHistory.size() && newMessages.size() < 30; i++) {
            Message msg = contextHistory.get(i);
            if (msg.role() == Role.SYSTEM) continue;
            if (msg.role() == Role.TOOL && msg.content() != null && msg.content().length() > 200) {
                newMessages.add(new Message(Role.TOOL,
                    msg.content().substring(0, 200) + "...",
                    null, msg.toolCallId()));
            } else {
                newMessages.add(msg);
            }
        }

        if (newMessages.isEmpty()) return;

        log.info("[Engine] L3 自动提取: {} 条新消息, {} tokens ({:.0f}%)",
            newMessages.size(), tokens, 100.0 * tokens / contextWindow);

        // 静默 LLM 调用
        List<Message> extractMsgs = new ArrayList<>();
        extractMsgs.add(Message.system(EXTRACTION_PROMPT));
        extractMsgs.add(Message.user(formatMessagesForExtraction(newMessages)));

        try {
            Message result = provider.generate(extractMsgs, EMPTY_TOOLS);
            if (result.content() == null || result.content().isBlank()) {
                log.debug("[Engine] L3 提取完成: 空结果");
                resetExtractionState(currentMsgCount);
                return;
            }
            List<MemoryEntry> entries = parseExtractionResult(result.content());
            if (entries.isEmpty()) {
                log.debug("[Engine] L3 提取完成: 无可提取记忆");
                resetExtractionState(currentMsgCount);
                return;
            }

            int saved = 0;
            for (MemoryEntry entry : entries) {
                if (saved >= 3) break;
                diskMemoryService.save(entry);
                saved++;
                log.info("[Engine] L3 记忆已保存: {} [{}]", entry.name(), entry.type());
            }
            setMemoryIndex(diskMemoryService.loadIndex());

        } catch (LLMException e) {
            log.warn("[Engine] L3 提取 LLM 调用失败: {}", e.getMessage());
            return; // 不重置计数器，下次满足条件重试
        } catch (Exception e) {
            log.warn("[Engine] L3 提取异常: {}", e.getMessage());
        }

        resetExtractionState(currentMsgCount);
    }

    private void resetExtractionState(int currentMsgCount) {
        turnsSinceLastExtraction = 0;
        messagesAtLastExtraction = currentMsgCount;
    }

    private List<MemoryEntry> parseExtractionResult(String content) {
        List<MemoryEntry> entries = new ArrayList<>();
        // 容错处理：允许 JSON 被 markdown 代码块包裹
        String json = content.strip();
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) {
                json = json.substring(start, end).strip();
            }
        }
        try {
            JsonNode arr = new ObjectMapper().readTree(json);
            if (!arr.isArray()) return entries;
            for (JsonNode item : arr) {
                String name = item.has("name") ? item.get("name").asText() : "";
                String desc = item.has("description") ? item.get("description").asText() : "";
                String typeStr = item.has("type") ? item.get("type").asText() : "reference";
                String body = item.has("content") ? item.get("content").asText() : "";
                if (name.isBlank() || body.isBlank()) continue;
                MemoryType type;
                try {
                    type = MemoryType.valueOf(typeStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    type = MemoryType.REFERENCE;
                }
                entries.add(new MemoryEntry(name, desc, type, Instant.now(), body));
            }
        } catch (Exception e) {
            log.debug("[Engine] L3 JSON 解析失败: {}", e.getMessage());
        }
        return entries;
    }

    private static String formatMessagesForExtraction(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("请分析以下对话片段：\n\n");
        int count = 0;
        for (Message msg : messages) {
            String role = msg.role().name().toLowerCase();
            String content = msg.content();
            if (content == null || content.isBlank()) {
                if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                    content = "[tool calls: " + msg.toolCalls().stream()
                        .map(tc -> tc.name())
                        .reduce((a, b) -> a + ", " + b).orElse("") + "]";
                } else {
                    continue;
                }
            }
            if (content.length() > 300) content = content.substring(0, 300) + "...";
            sb.append("[").append(role).append("] ").append(content).append("\n");
            count++;
            if (count > 40) {
                sb.append("...(truncated)\n");
                break;
            }
        }
        return sb.toString();
    }

    // ── 会话自动保存 ──────────────────────────────────────────────

    private void autoSaveSession() {
        if (sessionService == null || sessionAutoSaved) return;
        if (sessionHistory.size() <= 1) return;

        try {
            String name = autoSessionName();
            // Phase 3c: 只保存干净消息（不含 runtime 前缀）
            List<Message> clean = sessionHistory.stream()
                .filter(m -> !ContextBudgetAnalyzer.isRuntimeSystemMessage(m))
                .toList();
            if (clean.size() <= 1) return;
            sessionService.save(name, clean);
            sessionAutoSaved = true;
            log.info("[Engine] 会话已自动保存: {} ({} msgs, filtered from {})",
                name, clean.size(), sessionHistory.size());
        } catch (Exception e) {
            log.warn("[Engine] 自动保存失败: {}", e.getMessage());
        }
    }

    private String autoSessionName() {
        for (Message m : sessionHistory) {
            if (m.role() == Role.USER && m.content() != null && !m.content().isBlank()) {
                String c = m.content().strip();
                return "[auto] " + (c.length() > 50 ? c.substring(0, 50) + "..." : c);
            }
        }
        return "[auto] " + Instant.now().toString().substring(0, 16);
    }

    // ── 启动注入：相关历史会话 ──

    private void injectRelatedSessions() {
        if (sessionService == null) return;
        // 取首条用户消息作为搜索查询
        String query = null;
        for (Message m : sessionHistory) {
            if (m.role() == Role.USER && m.content() != null && !m.content().isBlank()) {
                query = m.content();
                break;
            }
        }
        if (query == null || query.isBlank()) return;

        try {
            List<SessionMeta> related = sessionService.search(query);
            if (related.isEmpty()) return;

            StringBuilder sb = new StringBuilder("[Related Past Sessions]\n");
            int count = 0;
            for (SessionMeta m : related) {
                if (count >= 3) break;
                String date = m.updatedAt().toString().substring(0, 10);
                sb.append("- (").append(date).append(") ").append(m.name());
                if (m.summary() != null && !m.summary().isBlank()) {
                    sb.append(": ").append(m.summary());
                }
                sb.append("\n");
                count++;
            }
            ephemeralContext.memory().add(Message.system(sb.toString().stripTrailing()));
            log.info("[Engine] 注入 {} 条相关历史会话到 ephemeral ctx", count);
        } catch (Exception e) {
            log.warn("[Engine] 历史会话注入失败: {}", e.getMessage());
        }
    }

    // ── session_context tool handler ──

    private String searchSessionContext(ToolCall call) {
        if (sessionService == null) {
            return "[H-003] Session service not available.";
        }
        JsonNode args = call.arguments();
        String query = args.has("query") ? args.get("query").asText() : "";
        if (query.isBlank()) {
            return "No search query provided. Please specify what to search for in past sessions.";
        }
        List<SessionMeta> matches = sessionService.search(query);
        if (matches.isEmpty()) {
            return "No past sessions matching \"" + query + "\". "
                + "You can save the current session with /session save <name> for future reference.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(matches.size()).append(" matching session(s):\n\n");
        for (SessionMeta m : matches) {
            String updated = m.updatedAt().toString().replace("T", " ").substring(0, 16);
            sb.append("- **").append(m.id()).append("**: ").append(m.name())
                .append(" (").append(m.messageCount()).append(" msgs, ").append(updated).append(")\n");
            if (m.summary() != null && !m.summary().isBlank()) {
                sb.append("  ").append(m.summary()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("To load a session, tell the user to use `/session load <id>`.");
        return sb.toString();
    }

    // ── skill_load / skill_unload / memory_save handlers ──

    private String handleSkillLoad(ToolCall call) {
        if (skillLoader == null) {
            return "[S-001] Skill system not available.";
        }
        JsonNode args = call.arguments();
        String name = args.has("name") ? args.get("name").asText() : "";
        if (name.isBlank()) {
            return "Skill name is required. Available skills: "
                + String.join(", ", skillCatalog.entries().keySet());
        }
        if (activeSkills.containsKey(name)) {
            return "Skill '" + name + "' is already loaded.";
        }
        String prompt = skillLoader.loadPrompt(name);
        if (prompt == null) {
            return "[S-001] Skill '" + name + "' not found.";
        }
        activeSkills.put(name, prompt);
        rebuildSystemPrompt();
        Path skillDir = skillLoader.skillDir(name);
        log.info("[Engine] Skill loaded: {} ({} chars)", name, prompt.length());
        String msg = "Skill '" + name + "' loaded (" + prompt.length() + " chars).\n"
            + "Skill directory: " + (skillDir != null ? skillDir.toString() : "N/A") + "\n"
            + "Reference files in this directory can be read with relative paths.";
        return msg;
    }

    private String handleSkillUnload(ToolCall call) {
        JsonNode args = call.arguments();
        String name = args.has("name") ? args.get("name").asText() : "";
        if (name.isBlank()) {
            return "Skill name is required. Loaded skills: "
                + String.join(", ", activeSkills.keySet());
        }
        String removed = activeSkills.remove(name);
        if (removed == null) {
            return "Skill '" + name + "' is not currently loaded. "
                + "Loaded: " + String.join(", ", activeSkills.keySet());
        }
        rebuildSystemPrompt();
        log.info("[Engine] Skill unloaded: {}", name);
        return "Skill '" + name + "' unloaded.";
    }

    // ── V3.7 子目标跟踪 ──

    private void trackTodoProgress(JsonNode args) {
        List<String> current = extractTodoContents(args);
        if (current.isEmpty()) return;
        if (current.equals(previousTodoContents)) {
            turnsWithoutTodoProgress++;
            log.debug("[TodoTrack] 子目标无进展: {} turns", turnsWithoutTodoProgress);
        } else {
            if (turnsWithoutTodoProgress > 0) {
                log.info("[TodoTrack] 子目标恢复进展 after {} stalled turns", turnsWithoutTodoProgress);
            }
            turnsWithoutTodoProgress = 0;
            previousTodoContents = current;
        }
    }

    private List<String> extractTodoContents(JsonNode args) {
        JsonNode todosNode = args.get("todos");
        if (todosNode == null || !todosNode.isArray()) return List.of();
        List<String> items = new ArrayList<>();
        for (JsonNode item : todosNode) {
            String content = item.has("content") ? item.get("content").asText() : "";
            String status = item.has("status") ? item.get("status").asText() : "pending";
            items.add(content + "|" + status);
        }
        return items;
    }

    private boolean allTodosCompleted() {
        if (previousTodoContents.isEmpty()) return false;
        return previousTodoContents.stream().allMatch(s -> s.endsWith("|completed"));
    }

    // ── Phase 3b: ModelContext 组装 ──

    /** 每轮构造给 provider 的完整 ModelContext */
    private List<Message> assembleModelContext() {
        List<Message> ctx = new ArrayList<>();
        ctx.addAll(ephemeralContext.memory());      // related sessions + working memory + conversation summary
        ctx.addAll(ephemeralContext.workspace());   // todo.md / plan.md
        ctx.addAll(sessionHistory);     // persistent messages
        ctx.addAll(ephemeralContext.runtime());     // loop detection, progress reminders
        return ctx;
    }

    /** 估算工具定义的 token 数 */
    private int estimateToolTokens() {
        var tools = registry.getAvailableTools();
        if (tools == null || tools.isEmpty()) return 0;
        int total = 0;
        for (var tool : tools) {
            Object schema = tool.inputSchema();
            if (schema != null) total += tokenizer.countTokens(schema.toString());
            total += tokenizer.countTokens(tool.name() != null ? tool.name() : "");
            total += tokenizer.countTokens(tool.description() != null ? tool.description() : "");
        }
        return total;
    }

    /** 过滤出可持久化的消息（不含 runtime 前缀的 SYSTEM 消息） */
    private List<Message> filterPersistable(List<Message> messages) {
        return messages.stream()
            .filter(m -> !ContextBudgetAnalyzer.isRuntimeSystemMessage(m))
            .toList();
    }

    // ── 工作区状态外部化 ──

    private void sniffWorkspaceState() {
        Path dotClawkit = Path.of(workDir, ".clawkit");
        StringBuilder state = new StringBuilder();

        Path todoMd = dotClawkit.resolve("todo.md");
        if (Files.exists(todoMd)) {
            try {
                String content = Files.readString(todoMd);
                if (!content.isBlank()) {
                    state.append("\n## 当前任务进度 (from .clawkit/todo.md)\n\n").append(content);
                }
            } catch (Exception e) {
                log.debug("[Engine] 读取 .clawkit/todo.md 失败: {}", e.getMessage());
            }
        }

        Path planMd = dotClawkit.resolve("plan.md");
        if (Files.exists(planMd)) {
            try {
                String content = Files.readString(planMd);
                if (!content.isBlank()) {
                    state.append("\n## 当前计划 (from .clawkit/plan.md)\n\n").append(content);
                }
            } catch (Exception e) {
                log.debug("[Engine] 读取 .clawkit/plan.md 失败: {}", e.getMessage());
            }
        }

        if (!state.isEmpty()) {
            String injected = "[Workspace State] 以下是上次中断前的工作进度，请据此续接：\n" + state;
            ephemeralContext.workspace().add(Message.system(injected));
            log.info("[Engine] 注入工作区状态到 ephemeral ctx ({} chars)", injected.length());
        }
    }

    private void syncTodoToWorkspace(JsonNode args) {
        Path dotClawkit = Path.of(workDir, ".clawkit");
        try {
            JsonNode todosNode = args.get("todos");
            if (todosNode == null || !todosNode.isArray()) return;

            StringBuilder md = new StringBuilder();
            for (JsonNode item : todosNode) {
                String status = item.has("status") ? item.get("status").asText() : "pending";
                String content = item.has("content") ? item.get("content").asText() : "";
                String activeForm = item.has("activeForm") ? item.get("activeForm").asText() : "";
                md.append("- [");
                md.append(switch (status) {
                    case "completed" -> "x";
                    case "in_progress" -> "~";
                    default -> " ";
                });
                md.append("] ");
                md.append("in_progress".equals(status) && !activeForm.isBlank() ? activeForm : content);
                md.append("\n");
            }

            Files.createDirectories(dotClawkit);
            Files.writeString(dotClawkit.resolve("todo.md"), "# TODO\n\n" + md);
            log.info("[Engine] TODO.md 已同步到 .clawkit/todo.md");
        } catch (Exception e) {
            log.warn("[Engine] 同步 TODO.md 失败: {}", e.getMessage());
        }
    }

    public void writePlanToWorkspace(String planContent) {
        if (planContent == null || planContent.isBlank()) return;
        Path dotClawkit = Path.of(workDir, ".clawkit");
        try {
            Files.createDirectories(dotClawkit);
            Files.writeString(dotClawkit.resolve("plan.md"), planContent);
            log.info("[Engine] PLAN.md 已同步到 .clawkit/plan.md");
        } catch (Exception e) {
            log.warn("[Engine] 同步 PLAN.md 失败: {}", e.getMessage());
        }
    }

    // === Plan-and-Execute ===

    public void setExecutionMode(ExecutionMode mode) {
        this.executionMode = mode;
        if (mode == ExecutionMode.PLAN_EXECUTE && planExecutor == null) {
            this.planExecutor = new PlanExecutor(provider, registry, workDir);
        }
        rebuildSystemPrompt();
        log.info("[Engine] 执行模式切换为: {}", mode);
    }

    public ExecutionMode executionMode() {
        return executionMode;
    }

    public void setOnPlanReady(Predicate<ExecutionPlan> handler) {
        this.onPlanReady = handler;
    }

    /**
     * Plan-and-Execute 流程：
     * 1. 读取工作区状态
     * 2. LLM 生成任务 DAG（无工具）
     * 3. 解析 JSON → ExecutionPlan
     * 4. 写入 plan.md + 展示计划
     * 5. 交互确认
     * 6. PlanExecutor 逐级执行
     * 7. 同步结果到工作区
     * 8. 返回摘要
     */
    private String runPlanExecute(String userPrompt) {
        fireState(AgentState.PLANNING, 0);

        // ── Phase 1: 读工作区状态 ──
        String existingPlanMd = readWorkspaceFile(".clawkit/plan.md");
        String existingTodoMd = readWorkspaceFile(".clawkit/todo.md");

        // ── Phase 2: 规划提示词 → LLM → JSON ──
        String planningPrompt = PlannerPrompt.buildInitialPrompt(userPrompt, existingPlanMd, existingTodoMd);
        List<Message> planMessages = new ArrayList<>();
        planMessages.add(Message.system("You are a task planner. Output pure JSON only."));
        planMessages.add(Message.user(planningPrompt));

        log.info("[PlanExecute] 规划阶段: 调用 LLM 生成任务计划...");
        Message planResponse;
        try {
            planResponse = provider.generate(planMessages, List.of());
        } catch (LLMException e) {
            fireState(AgentState.ERROR, 0, Map.of("error", e.getMessage()));
            return "[A-002] 规划阶段 LLM 调用失败: " + e.getMessage();
        }

        String planJson = planResponse.content() != null ? planResponse.content() : "";
        if (planJson.isBlank()) {
            return "[P-001] LLM 未返回计划内容";
        }

        // ── Phase 3: 解析计划 ──
        Result<ExecutionPlan> parseResult = new PlanParser().parse(planJson);
        if (parseResult instanceof Result.Err<ExecutionPlan> err) {
            return "[P-00x] 计划解析失败: " + err.error().message();
        }
        ExecutionPlan plan = ((Result.Ok<ExecutionPlan>) parseResult).data();

        // ── Phase 4: 写入 plan.md 持久化 ──
        String planMarkdown = formatPlanAsMarkdown(plan);
        writePlanToWorkspace(planMarkdown);

        // ── Phase 5: 交互确认 ──
        if (onPlanReady != null) {
            boolean confirmed = onPlanReady.test(plan);
            if (!confirmed) {
                plan.setStatus(PlanStatus.CANCELLED);
                log.info("[PlanExecute] 用户取消计划");
                return "计划已取消。用 /auto 或 /ask 切换到执行模式手动执行。";
            }
        }

        // ── Phase 6: 执行 ──
        fireState(AgentState.EXECUTING, 0, Map.of("taskCount", plan.taskCount()));
        log.info("[PlanExecute] 执行阶段: {} tasks", plan.taskCount());

        if (planExecutor == null) {
            planExecutor = new PlanExecutor(provider, registry, workDir);
        }
        ExecutionPlan completedPlan = planExecutor.execute(plan);

        // ── Phase 7: 同步结果 ──
        syncPlanResultsToWorkspace(completedPlan);
        fireState(AgentState.REPLYING, completedPlan.taskCount());

        // ── Phase 8: 返回摘要 ──
        return buildPlanSummary(completedPlan);
    }

    private String readWorkspaceFile(String path) {
        try {
            Path file = Path.of(workDir, path);
            if (Files.exists(file)) {
                String content = Files.readString(file);
                return content.isBlank() ? null : content;
            }
        } catch (Exception e) {
            log.debug("[Engine] 读取 {} 失败: {}", path, e.getMessage());
        }
        return null;
    }

    private String formatPlanAsMarkdown(ExecutionPlan plan) {
        StringBuilder md = new StringBuilder();
        md.append("# Plan: ").append(plan.getGoal()).append("\n\n");
        List<List<String>> levels = PlanParser.computeLevels(plan.getTasks());
        for (int i = 0; i < levels.size(); i++) {
            md.append("## Level ").append(i).append("\n\n");
            for (String taskId : levels.get(i)) {
                Task task = plan.getTask(taskId);
                if (task == null) continue;
                md.append("- **").append(taskId).append("** [").append(task.getTaskType())
                    .append("]: ").append(task.getDescription()).append("\n");
                if (!task.getDependencies().isEmpty()) {
                    md.append("  - depends on: ").append(String.join(", ", task.getDependencies())).append("\n");
                }
            }
            md.append("\n");
        }
        return md.toString();
    }

    private void syncPlanResultsToWorkspace(ExecutionPlan plan) {
        StringBuilder md = new StringBuilder();
        md.append("# Execution Plan\n\n");
        md.append("Goal: ").append(plan.getGoal()).append("\n\n");
        md.append("Status: ").append(plan.getStatus()).append("\n\n");

        for (String taskId : plan.getExecutionOrder()) {
            Task task = plan.getTask(taskId);
            if (task == null) continue;
            md.append("### ").append(taskId).append(": ").append(task.getDescription()).append("\n");
            md.append("- Type: ").append(task.getTaskType()).append("\n");
            md.append("- Status: ").append(task.getStatus()).append("\n");
            if (task.getResult() != null && !task.getResult().isBlank()) {
                String result = task.getResult();
                md.append("- Result: ").append(result.length() > 200
                    ? result.substring(0, 200) + "..." : result).append("\n");
            }
            if (task.getErrorMessage() != null) {
                md.append("- Error: ").append(task.getErrorMessage()).append("\n");
            }
            md.append("\n");
        }
        md.append("Summary: ").append(plan.getSummary() != null ? plan.getSummary() : "").append("\n");

        writePlanToWorkspace(md.toString());
    }

    private String buildPlanSummary(ExecutionPlan plan) {
        long total = plan.taskCount();
        long completed = plan.getTasks().values().stream()
            .filter(t -> t.getStatus() == TaskStatus.COMPLETED).count();
        long failed = plan.getTasks().values().stream()
            .filter(t -> t.getStatus() == TaskStatus.FAILED).count();
        long skipped = plan.getTasks().values().stream()
            .filter(t -> t.getStatus() == TaskStatus.SKIPPED).count();

        return "## 执行完成\n\n"
            + "目标: " + plan.getGoal() + "\n\n"
            + "结果: " + completed + "/" + total + " 成功"
            + (failed > 0 ? ", " + failed + " 失败" : "")
            + (skipped > 0 ? ", " + skipped + " 跳过" : "")
            + "\n\n"
            + (plan.getSummary() != null ? plan.getSummary() : "");
    }

    private String handleRemember(ToolCall call) {
        JsonNode args = call.arguments();
        String key = args.has("key") ? args.get("key").asText() : "";
        String value = args.has("value") ? args.get("value").asText() : "";
        if (key.isBlank() || value.isBlank()) {
            return "key and value are required for remember.";
        }
        workingMemory.put(key, value);
        log.info("[WM] remembered: {} ({} chars)", key, value.length());
        return "Stored: " + key + " (" + value.length() + " chars). "
            + workingMemory.size() + " keys in working memory.";
    }

    private String handleMemorySave(ToolCall call) {
        if (diskMemoryService == null) {
            return "[S-003] Memory service not available.";
        }
        JsonNode args = call.arguments();
        String name = args.has("name") ? args.get("name").asText() : "";
        String description = args.has("description") ? args.get("description").asText() : "";
        String typeStr = args.has("type") ? args.get("type").asText() : "reference";
        String content = args.has("content") ? args.get("content").asText() : "";

        if (name.isBlank() || content.isBlank()) {
            return "name and content are required for memory_save.";
        }

        MemoryType type;
        try {
            type = MemoryType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            type = MemoryType.REFERENCE;
        }

        MemoryEntry entry = new MemoryEntry(name, description, type, Instant.now(), content);
        diskMemoryService.save(entry);
        setMemoryIndex(diskMemoryService.loadIndex());
        log.info("[Engine] Memory saved: {} [{}]", name, type);
        return "Memory saved: " + entry.filename() + " [" + type.name().toLowerCase() + "] — " + description;
    }

    // ── session management methods ──

    @Override
    public String saveSession(String name) {
        if (sessionService == null) {
            return "[H-003] Session service not available.";
        }
        if (sessionHistory.size() <= 1) {
            return "[H-001] No conversation to save — start a conversation first.";
        }
        SessionMeta meta = sessionService.save(name, sessionHistory);
        return String.format("Session saved: %s (%s, %d messages)",
            meta.id(), meta.name(), meta.messageCount());
    }

    @Override
    public void loadSession(String sessionId) {
        if (sessionService == null) {
            throw new UnsupportedOperationException("Session service not available");
        }
        List<Message> loaded = sessionService.load(sessionId);
        clearSession();
        sessionHistory.addAll(loaded);
        log.info("[Engine] Session loaded: {} ({} messages)", sessionId, loaded.size());
    }

    @Override
    public List<SessionMeta> listSessions() {
        if (sessionService == null) return List.of();
        return sessionService.list();
    }

    @Override
    public void deleteSession(String sessionId) {
        if (sessionService == null) {
            throw new UnsupportedOperationException("Session service not available");
        }
        sessionService.delete(sessionId);
        log.info("[Engine] Session deleted: {}", sessionId);
    }

    @Override
    public String newSession() {
        clearSession();
        log.info("[Engine] New session started");
        return "Session cleared. Ready for new conversation.";
    }

    private Message lastAssistantMessage(List<Message> contextHistory) {
        for (int i = contextHistory.size() - 1; i >= 0; i--) {
            if (contextHistory.get(i).role() == Role.ASSISTANT) {
                return contextHistory.get(i);
            }
        }
        return null;
    }

    @Deprecated
    public void setPermissionHandler(Predicate<ToolCall> handler) {
        this.approvalHandler = req -> handler.test(
            new ToolCall("", req.toolName(), com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()))
            ? new ApprovalResult.Approve() : new ApprovalResult.Reject("denied");
    }

    public void setApprovalHandler(ApprovalHandler handler) {
        this.approvalHandler = handler;
    }

    public void setSessionService(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    public void setWorkspaceRules(String rules) {
        this.l2WorkspaceRules = rules != null ? rules : "";
        rebuildSystemPrompt();
        log.info("[Engine] 工作区守则已加载 ({} chars)", l2WorkspaceRules.length());
    }

    public void setSkillLoader(SkillLoader loader) {
        this.skillLoader = loader;
    }

    public void setDiskMemoryService(DiskMemoryService svc) {
        this.diskMemoryService = svc;
    }

    public void rebuildSkillCatalog(SkillCatalog catalog) {
        this.skillCatalog = catalog != null ? catalog : SkillCatalog.empty();
        rebuildSystemPrompt();
        log.info("[Engine] 技能目录已加载: {} skills", this.skillCatalog.entries().size());
    }

    public void loadSkill(String name, String prompt) {
        activeSkills.put(name, prompt);
        rebuildSystemPrompt();
        log.info("[Engine] Skill loaded via CLI: {} ({} chars)", name, prompt.length());
    }

    public void unloadSkill(String name) {
        activeSkills.remove(name);
        rebuildSystemPrompt();
        log.info("[Engine] Skill unloaded via CLI: {}", name);
    }

    public boolean hasSkillLoaded(String name) {
        return activeSkills.containsKey(name);
    }

    public void setMemoryIndex(String memoryIndex) {
        this.l5MemoryIndex = memoryIndex != null ? memoryIndex : "";
        rebuildSystemPrompt();
        log.info("[Engine] 记忆索引已更新{}", sessionHistory.isEmpty() ? "" : " (system prompt rebuilt)");
    }

    public void setOnThinkingBegin(Runnable callback) {
        this.onThinkingBegin = callback;
    }

    public void onReasoning(Consumer<String> callback) {
        if (callback != null) onReasoningListeners.add(callback);
    }

    public void onThinkingToken(Consumer<String> callback) {
        if (callback != null) onThinkingTokenListeners.add(callback);
    }

    public void onSubAgentSpawn(Consumer<SubAgentSpawnEvent> listener) {
        if (listener != null) onSubAgentSpawnListeners.add(listener);
    }

    public void onSubAgentComplete(Consumer<SubAgentCompleteEvent> listener) {
        if (listener != null) onSubAgentCompleteListeners.add(listener);
    }

    public void onToolStart(Consumer<ToolStartEvent> listener) {
        if (listener != null) onToolStartListeners.add(listener);
    }

    public void onToolEnd(Consumer<ToolEndEvent> listener) {
        if (listener != null) onToolEndListeners.add(listener);
    }

    private void fireToolStart(ToolCall call) {
        if (onToolStartListeners.isEmpty()) return;
        String summary = buildArgSummary(call);
        var event = new ToolStartEvent(call.name(), summary);
        for (var l : onToolStartListeners) {
            try { l.accept(event); } catch (Exception ignored) {}
        }
    }

    private void fireToolEnd(ToolCall call, ToolResult result) {
        if (onToolEndListeners.isEmpty()) return;
        boolean success = !result.isError();
        String detail = success
            ? result.output().length() + " bytes"
            : result.output().substring(0, Math.min(60, result.output().length()));
        var event = new ToolEndEvent(call.name(), success, detail);
        for (var l : onToolEndListeners) {
            try { l.accept(event); } catch (Exception ignored) {}
        }
    }

    // ── RunEvent 观测 ───────────────────────────────────────────────

    /** 注册运行事件监听器（如 FileRunRecorder） */
    public void onRunEvent(Consumer<RunEvent> listener) {
        if (listener != null) onRunEventListeners.add(listener);
    }

    /** 移除运行事件监听器 */
    public void removeOnRunEventListener(Consumer<RunEvent> listener) {
        if (listener != null) onRunEventListeners.remove(listener);
    }

    /** 分发运行事件给所有监听器（吞异常，不中断主循环） */
    private void fireRunEvent(RunEvent event) {
        if (onRunEventListeners.isEmpty()) return;
        for (var l : onRunEventListeners) {
            try { l.accept(event); } catch (Exception ignored) {}
        }
    }

    /** 为 internal tool 构造 ToolCallMetrics */
    private ToolCallMetrics internalToolMetrics(ToolCall call, boolean readOnly, String output) {
        return new ToolCallMetrics(currentRunId, currentTurnNumber,
            call.id(), call.name(), "", readOnly, true, false, null,
            true, 0, output.length(), false, null, null);
    }

    /** 生成 run ID：yyyyMMdd-HHmmss-4位十六进制随机数 */
    private static String generateRunId() {
        String ts = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        int suffix = (int) (Math.random() * 0x10000);
        return ts + "-" + String.format("%04x", suffix);
    }

    /** 提取工具调用中最有辨识度的参数，用于 CLI 可视化 */
    private static String buildArgSummary(ToolCall call) {
        var args = call.arguments();
        if (args == null) return "";
        // 按优先级匹配高频参数字段
        for (String key : List.of("path", "file_path", "pattern", "command", "query", "url")) {
            if (args.has(key)) {
                String val = args.get(key).asText();
                return key + "=" + (val.length() > 60 ? val.substring(0, 57) + "..." : val);
            }
        }
        // fallback: 第一个字段
        var it = args.fields();
        if (it.hasNext()) {
            var entry = it.next();
            String val = entry.getValue().asText();
            return entry.getKey() + "=" + (val.length() > 60 ? val.substring(0, 57) + "..." : val);
        }
        return "";
    }

    @Override
    public void onStateChange(Consumer<AgentStateEvent> listener) {
        if (listener != null) onStateChangeListeners.add(listener);
    }

    public void removeOnStateChangeListener(Consumer<AgentStateEvent> listener) {
        if (listener != null) onStateChangeListeners.remove(listener);
    }

    @Override
    public void setOnToken(Consumer<String> callback) {
        onTokenListeners.clear();
        if (callback != null) onTokenListeners.add(callback);
    }

    @Override
    public void addOnTokenListener(Consumer<String> listener) {
        if (listener != null) onTokenListeners.add(listener);
    }

    @Override
    public void removeOnTokenListener(Consumer<String> listener) {
        if (listener != null) onTokenListeners.remove(listener);
    }

    private void fireState(AgentState state, int turnCount, Map<String, Object> metadata) {
        AgentStateEvent event = new AgentStateEvent(state, turnCount, metadata);
        for (var listener : onStateChangeListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.warn("[Engine] State listener error: {}", e.getMessage());
            }
        }
    }

    private void fireState(AgentState state, int turnCount) {
        fireState(state, turnCount, Map.of());
    }

    @Override
    public boolean tryAcquire() {
        return busy.compareAndSet(false, true);
    }

    @Override
    public void release() {
        busy.set(false);
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public int getEstimatedTokens() {
        return contextManager.estimateTokens(sessionHistory);
    }

    public MaskedContext getLastMaskedContext() {
        return lastMaskedContext;
    }

    public ContextBudgetReport getContextBudgetReport() {
        if (lastBudgetReport != null) return lastBudgetReport;
        // 如果没有缓存报告，即时分析
        int toolDefTokens = estimateToolTokens();
        return budgetAnalyzer.analyze(sessionHistory, toolDefTokens, Map.of());
    }

    @Override
    public int getMessageCount() {
        return sessionHistory.size();
    }

    @Override
    public Map<String, Integer> getTokenBreakdown() {
        int systemTokens = 0, userTokens = 0, assistantTokens = 0, toolTokens = 0;
        for (Message m : sessionHistory) {
            int t = contextManager.estimateTokens(List.of(m));
            switch (m.role()) {
                case SYSTEM    -> systemTokens += t;
                case USER      -> userTokens += t;
                case ASSISTANT -> assistantTokens += t;
                case TOOL      -> toolTokens += t;
            }
        }
        Map<String, Integer> bd = new java.util.LinkedHashMap<>();
        bd.put("system", systemTokens);
        bd.put("user", userTokens);
        bd.put("assistant", assistantTokens);
        bd.put("tool", toolTokens);
        return bd;
    }

    @Override
    public String getCompactionStatus() {
        CompactionStatus c = lastCompaction;
        if (c == null || c.beforeTokens() == 0) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(c.beforeTokens()).append("->").append(c.afterTokens()).append(" tokens");
        if (c.mapBatches() > 0) {
            sb.append(", map:").append(c.mapBatches())
              .append(" reduce:").append(c.usedReduce() ? "yes" : "no");
        }
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    public String compactSession() {
        int before = contextManager.estimateTokens(sessionHistory);
        if (sessionHistory.isEmpty()) {
            return "session is empty, nothing to compact.";
        }
        List<Message> compacted = contextManager.compact(sessionHistory, contextWindow);
        int after = contextManager.estimateTokens(compacted);
        // Phase 3c: 只保存干净消息
        List<Message> clean = filterPersistable(compacted);
        sessionHistory.clear();
        sessionHistory.addAll(clean);
        recentCallSignatures.clear();
        int reduction = before > 0 ? (int) (100.0 * (before - after) / before) : 0;
        log.info("[Engine] 手动压缩: {} → {} tokens (减少 {}%)", before, after, reduction);
        return String.format("compacted: %s → %s tokens (%d%% reduced)",
            formatTokenCount(before), formatTokenCount(after), reduction);
    }

    private String summarizeForCompact(java.util.List<Message> messages) {
        Message result = provider.generate(messages, EMPTY_TOOLS);
        return result.content() != null ? result.content() : "";
    }

    /**
     * 清洗 DeepSeek 在无工具阶段幻想出的 &lt;tool_calls&gt; XML 文本，
     * 防止伪工具调用污染 Phase 2 上下文和用户显示。
     */
    private static String stripFakeToolCalls(String content) {
        if (content == null || content.isEmpty()) return content;
        // 去掉 <tool_calls>...</tool_calls> 整块（含多行）
        String cleaned = content.replaceAll(
            "(?s)<\\s*tool_calls\\s*>.*?</\\s*tool_calls\\s*>", "");
        // 去掉孤立的 <invoke>...</invoke> 块
        cleaned = cleaned.replaceAll(
            "(?s)<\\s*invoke\\s+name\\s*=\\s*\"[^\"]*\">.*?</\\s*invoke\\s*>", "");
        // 压缩多余空行
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n");
        return cleaned.trim();
    }

    private static String formatTokenCount(int tokens) {
        if (tokens < 1000) return String.valueOf(tokens);
        return String.format("%.1fk", tokens / 1000.0);
    }

    public String workDir() {
        return workDir;
    }

    public ThinkingMode thinkingMode() {
        return thinkingMode;
    }
}
