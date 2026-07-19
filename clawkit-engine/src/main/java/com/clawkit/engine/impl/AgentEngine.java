package com.clawkit.engine.impl;

import com.clawkit.context.CompactionResult;
import com.clawkit.context.ContextBudgetAnalyzer;
import com.clawkit.context.ContextBudgetReport;
import com.clawkit.context.ContextRequest;
import com.clawkit.context.PromptAssembly;
import com.clawkit.context.SkillCatalog;
import com.clawkit.context.impl.MessageMasker.MaskedContext;
import java.nio.file.Path;
import com.clawkit.engine.AgentLoop;
import com.clawkit.engine.AgentRuntimeDependencies;
import com.clawkit.engine.AgentState;
import com.clawkit.engine.AgentStateEvent;
import com.clawkit.engine.ApprovalHandler;
import com.clawkit.engine.ApprovalResult;
import com.clawkit.engine.PermissionMode;
import com.clawkit.engine.ProviderGateway;
import com.clawkit.engine.SkillRuntime;
import com.clawkit.engine.MemoryHooks;
import com.clawkit.engine.RunPhase;
import com.clawkit.engine.RunScope;
import com.clawkit.engine.SessionMeta;
import com.clawkit.engine.SubAgentSpawnEvent;
import com.clawkit.engine.SubAgentCompleteEvent;
import com.clawkit.engine.ToolStartEvent;
import com.clawkit.engine.ToolEndEvent;
import com.clawkit.provider.ModelRequest;
import com.clawkit.tools.ApprovalGrantCache;
import com.clawkit.tools.DefaultApprovalGrantCache;
import com.clawkit.engine.SessionService;
import com.clawkit.engine.ExecutionMode;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.tools.schema.ExecutionPlan;
import com.clawkit.memory.MemoryEntry;
import com.clawkit.provider.LLMException;
import com.clawkit.provider.LLMProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.clawkit.tools.Registry;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.Role;
import com.clawkit.tools.schema.ToolCall;
import com.clawkit.tools.schema.ToolDefinition;
import com.clawkit.tools.ToolExecutionResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.clawkit.observability.CompactCompletedPayload;
import com.clawkit.observability.CompactTriggeredPayload;
import com.clawkit.observability.ObservabilityRedactor;
import com.clawkit.observability.RunCompletedPayload;
import com.clawkit.observability.RunEventPayload;
import com.clawkit.observability.RunRecorder;
import com.clawkit.observability.RunStartedPayload;
import com.clawkit.observability.RunStatus;
import com.clawkit.observability.TurnCompletedPayload;
import com.clawkit.observability.TurnStartedPayload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentEngine — clawkit 的核心驱动。
 * 组装 LLMProvider + Registry，驱动 ReAct 循环。
 * 支持两种思考模式：OFF（单阶段）和 TWO_STAGE（先规划再执行）。
 */
public class AgentEngine implements AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    // ── 5-layer prompt architecture ──────────────────────────────────

    private static final String L1_KERNEL =
        "You are clawkit，一个专业的编程助手。";

    private volatile String l2WorkspaceRules = "";
    private volatile SkillCatalog skillCatalog = SkillCatalog.empty();
    private final SkillRuntime skillRuntime;
    private volatile String l5MemoryIndex = "";

    private final MemoryHooks memoryHooks;

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
            Map.of(),
            buildL4ModePrompt(),
            l5MemoryIndex);
    }

    private void rebuildSystemPrompt() {
        // The stable prompt is assembled by ContextPipeline for each request.
        // It is deliberately not stored in factual session history.
    }

    private static final int MAX_TURNS = 50;
    private static final int DEAD_LOOP_THRESHOLD = 3;
    private static final int PROGRESS_REMINDER_INTERVAL = 10;
    private static final List<ToolDefinition> EMPTY_TOOLS = Collections.emptyList();

    private final Registry registry;
    private final String workDir;
    private final WorkspaceStateStore workspaceState;
    private final EngineContextCoordinator context;
    private final ConversationSession session = new ConversationSession();
    private final List<String> recentCallSignatures = new ArrayList<>();
    private volatile ThinkingMode thinkingMode;
    private volatile ExecutionMode executionMode = ExecutionMode.REACT;
    private volatile PlanRunCoordinator planCoordinator;
    private volatile Predicate<ExecutionPlan> onPlanReady;
    private volatile PermissionMode permissionMode = PermissionMode.AUTO;
    private volatile ApprovalHandler approvalHandler;
    private final ApprovalGrantCache approvalCache = new DefaultApprovalGrantCache();
    private volatile boolean interrupted;
    // P1-G1：run 级取消/deadline/预算控制
    private volatile com.clawkit.reliability.CancellationTree currentControl;
    private volatile com.clawkit.tools.control.ExecutionControl parentControl;
    private volatile java.time.Duration runDeadline;
    private volatile Long runTokenBudget;
    private volatile Long runProviderCallBudget;
    private volatile Long runToolCallBudget;
    volatile int lastRunTurns;
    volatile boolean enableSubAgents = true;
    private final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final EngineEventHub events;
    private volatile String currentRunId;
    private volatile String parentRunId;
    private final ToolCallExecutor toolCallExecutor;
    // P1-G4：唯一 Side Effect Gate（Attempt 生命周期 + 幂等 + 目标互斥）
    private final com.clawkit.reliability.gate.SideEffectGate sideEffectGate;
    private final InternalToolRouter internalToolRouter = new InternalToolRouter();
    private final SubAgentRunner subAgentRunner;
    private final InternalToolSuite internalTools;
    volatile MaskedContext lastMaskedContext;
    // All model calls pass through the gateway.
    private volatile ProviderGateway providerGateway;

    // Phase 3b: ephemeral 上下文容器
    private final EphemeralContext ephemeralContext = new EphemeralContext();

    // V3.7 子目标跟踪
    private List<String> previousTodoContents = List.of();
    private int turnsWithoutTodoProgress = 0;
    private static final int TODO_STALL_THRESHOLD = 3;


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

    /** Production constructor with explicit runtime dependencies. */
    public AgentEngine(AgentRuntimeDependencies deps, String workDir,
                       ThinkingMode thinkingMode, String memoryIndex) {
        this.providerGateway = deps.providerGateway();
        this.memoryHooks = deps.memoryHooks();
        this.skillRuntime = deps.skillRuntime();
        this.skillCatalog = skillRuntime.catalog();
        this.registry = deps.registry();
        this.workDir = workDir;
        this.workspaceState = new WorkspaceStateStore(Path.of(workDir));
        this.context = new EngineContextCoordinator(deps.contextWindow(), deps.encoding(),
            deps.contextPipeline(), this::summarizeForCompact);
        this.thinkingMode = thinkingMode;
        this.l5MemoryIndex = memoryIndex != null ? memoryIndex : "";
        this.sideEffectGate = createSideEffectGate(workDir, deps);
        this.toolCallExecutor = new ToolCallExecutor(registry, sideEffectGate);
        this.events = new EngineEventHub(deps.runRecorder());
        this.subAgentRunner = new SubAgentRunner(
            registry, providerGateway, context, workDir, events);
        this.internalTools = new InternalToolSuite(
            internalToolRouter, session, skillRuntime, memoryHooks,
            subAgentRunner, this::setMemoryIndex);
        this.planCoordinator = createPlanCoordinator();
    }

    // ── 旧构造器（保持向后兼容） ────────────────────────────────────

    /** @deprecated 使用 AgentRuntimeDependencies 构造器 */
    @Deprecated
    public AgentEngine(LLMProvider provider, Registry registry, String workDir,
                       ThinkingMode thinkingMode, Consumer<String> onReasoning,
                       Consumer<String> onToken, String memoryIndex) {
        this(provider, registry, workDir, thinkingMode, onReasoning, onToken, memoryIndex, true);
    }

    // 内部构造器（旧构造器适配：用 raw provider 创建 gateway wrapper）
    private AgentEngine(LLMProvider provider, Registry registry, String workDir,
                       ThinkingMode thinkingMode, Consumer<String> onReasoning,
                       Consumer<String> onToken, String memoryIndex, boolean legacyCompat) {
        this.registry = registry;
        this.memoryHooks = AgentRuntimeDependencies.noopMemoryHooks();
        this.skillRuntime = AgentRuntimeDependencies.emptySkillRuntime();
        this.workDir = workDir;
        this.workspaceState = new WorkspaceStateStore(Path.of(workDir));
        int legacyWindow = provider != null ? provider.getContextWindow() : 128_000;
        String legacyEncoding = provider != null ? provider.getEncoding() : "cl100k_base";
        this.context = new EngineContextCoordinator(legacyWindow, legacyEncoding, null,
            this::summarizeForCompact);
        this.thinkingMode = thinkingMode;
        this.events = new EngineEventHub(new com.clawkit.observability.CompositeRunRecorder());
        events.onReasoning(onReasoning);
        events.setToken(onToken);
        this.l5MemoryIndex = memoryIndex != null ? memoryIndex : "";
        this.sideEffectGate = createSideEffectGate(workDir, null);
        this.toolCallExecutor = new ToolCallExecutor(registry, sideEffectGate);
        // Compatibility constructors still use the same gateway and context pipeline.
        this.providerGateway = new com.clawkit.engine.impl.ObservingProviderGateway(
            provider, new com.clawkit.observability.CompositeRunRecorder());
        this.subAgentRunner = new SubAgentRunner(
            registry, providerGateway, context, workDir, events);
        this.internalTools = new InternalToolSuite(
            internalToolRouter, session, skillRuntime, memoryHooks,
            subAgentRunner, this::setMemoryIndex);
        this.planCoordinator = createPlanCoordinator();
    }

    @Override
    public String run(String userPrompt) {
        log.info("[Engine] 引擎启动，锁定工作区: {}, 思考模式: {}, 执行模式: {}",
            workDir, thinkingMode, executionMode);

        // P1-G1：每次 run 建立控制根（SubAgent 从父控制派生，共享预算并级联取消）
        com.clawkit.reliability.CancellationTree control = createRunControl();
        this.currentControl = control;
        if (interrupted) {
            control.cancel();
        }

        if (executionMode == ExecutionMode.PLAN_EXECUTE) {
            String runId = generateRunId();
            this.currentRunId = runId;
            try {
                return planCoordinator.run(userPrompt, runId, parentRunId,
                    permissionMode, thinkingMode, approvalHandler, onPlanReady, control);
            } finally {
                interrupted = false;
                if (currentControl == control) {
                    currentControl = null;
                }
            }
        }

        String currentRunId = generateRunId();
        this.currentRunId = currentRunId;
        this.events.resetRun();
        fireEvent(new RunStartedPayload(
            ObservabilityRedactor.summarizeTask(userPrompt),
            workDir, "gateway",
            permissionMode.name(), thinkingMode.name(), executionMode.name()), null);

        ephemeralContext.workspace().addAll(workspaceState.recallContext());
        session.append(Message.user(userPrompt));
        ephemeralContext.memory().addAll(memoryHooks.beforeRun(
            new MemoryHooks.MemoryRecallRequest(userPrompt, 5, Set.of())));
        ephemeralContext.memory().addAll(session.relatedContext(userPrompt));
        List<Message> contextHistory = new ArrayList<>(session.messages());

        int turnCount = 0;
        lastRunTurns = 0;
        fireState(AgentState.IDLE, 0);

        try {
        while (true) {
            if (interrupted || control.isCancelled()) {
                interrupted = false;
                fireState(AgentState.INTERRUPTED, turnCount);
                log.info("[Engine] 被用户中断，退出循环");
                completeRun(RunStatus.INTERRUPTED, null, null);
                return "[A-001] 已被用户中断。";
            }

            turnCount++;
            log.info("========== [Turn {}] 开始 ==========", turnCount);
            fireEvent(new TurnStartedPayload(), turnCount);

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
                completeRun(RunStatus.HARD_LIMIT,
                    "A-001", "达到最大迭代轮次 (" + MAX_TURNS + ")");
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

            ephemeralContext.memory().addAll(internalTools.workingMemoryContext());

            // Phase 3b: 组装 ModelContext（ContextPipeline 唯一入口）
            var ctx = context.build(buildContextRequest());
            List<Message> modelContext = new ArrayList<>(ctx.messages());
            // always-on 规则（始终执行，不依赖预算）
            modelContext = context.applyAlwaysOnRules(modelContext);

            // Phase 2: 上下文掩码 + 预算分析 + compact（ContextPipeline 聚合）
            int toolDefTokens = estimateToolTokens();
            CompactionResult cr = context.compact(modelContext, toolDefTokens, turnCount);
            modelContext = cr.messages();

            if (cr.compacted()) {
                fireEvent(new CompactTriggeredPayload(), turnCount);
                var beforeSections = new java.util.LinkedHashMap<String, Integer>();
                cr.beforeReport().sections().forEach((k, v) -> beforeSections.put(k.name(), v));
                var afterSections = new java.util.LinkedHashMap<String, Integer>();
                cr.afterReport().sections().forEach((k, v) -> afterSections.put(k.name(), v));
                fireEvent(new CompactCompletedPayload(
                    cr.beforeMessages(), cr.afterMessages(),
                    cr.beforeReport().totalTokens(), cr.afterReport().totalTokens(),
                    cr.beforeReport().status().name(), cr.afterReport().status().name(),
                    beforeSections, afterSections,
                    0, cr.appliedRules(), 0L, false, null), turnCount);
                session.replace(filterPersistable(modelContext));
                log.info("[Engine] compact: {} → {} msgs, {} → {} tokens",
                    cr.beforeMessages(), cr.afterMessages(),
                    cr.beforeReport().totalTokens(), cr.afterReport().totalTokens());
            }

            if (context.lastReport().status() == ContextBudgetReport.BudgetStatus.HARD_LIMIT) {
                log.warn("[Engine] compact 后仍超 95% 硬限制");
                completeRun(RunStatus.COMPACT_FAILED,
                    "A-001", "compact 后仍超 95% 硬限制");
                return String.format(
                    "上下文过大（%d / %d tokens），compact 后仍超 95%% 硬限制。",
                    context.lastReport().totalTokens(), context.policy().contextWindow());
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
                    events.thinkingBegin();

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
                    StringBuilder sb = new StringBuilder();
                    Message rawThinking;
                    rawThinking = callProvider(phase1Context, EMPTY_TOOLS, turnCount, RunPhase.TWO_STAGE_PLAN);
                    sb.append(rawThinking.content() != null ? rawThinking.content() : "");

                    // 清洗伪 XML tool_call（DeepSeek 可能在无工具阶段幻想出 tool_call 文本）
                    String cleanContent = stripFakeToolCalls(sb.toString());
                    thinkingMsg = new Message(rawThinking.role(), cleanContent,
                        rawThinking.toolCalls(), rawThinking.toolCallId());

                    if (!cleanContent.isEmpty()) {
                        log.debug("[推理] {}", cleanContent);
                    }
                    events.reasoning(cleanContent);
                    log.info("[Engine] 慢思考阶段1 完成");
                } catch (LLMException e) {
                    fireState(AgentState.ERROR, turnCount, Map.of("error", e.getMessage()));
                    log.error("[Engine] 慢思考阶段1 失败: {}", e.getMessage());
                    completeRun(RunStatus.PLANNING_ERROR, "A-002", e.getMessage());
                    return ProviderFailureMessage.format(e, "慢思考规划阶段");
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
            }
            availableTools = new ArrayList<>(availableTools);
            availableTools.addAll(internalTools.definitions(permissionMode, enableSubAgents));
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
            final Message responseMsg;
            try {
                responseMsg = callProvider(phase2Context, availableTools, turnCount, RunPhase.REACT);
            } catch (LLMException e) {
                fireState(AgentState.ERROR, turnCount, Map.of("error", e.getMessage()));
                log.error("[Engine] LLM 调用失败 (A-002): {}", e.getMessage());
                completeRun(RunStatus.LLM_ERROR, "A-002", e.getMessage());
                return ProviderFailureMessage.format(e);
            }
            contextHistory.add(responseMsg);
            int toolCallCount = responseMsg.toolCalls() != null ? responseMsg.toolCalls().size() : 0;

            // Provider events are emitted exclusively by ObservingProviderGateway.
            fireEvent(new TurnCompletedPayload(toolCallCount,
                responseMsg.content() != null && !responseMsg.content().isEmpty(),
                false, null, null), turnCount);

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
                session.replace(filterPersistable(contextHistory));
                completeRun(RunStatus.COMPLETED, null, null);
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

            // 通过 ToolCallExecutor 统一执行（包括 SubAgent task）
            calls.forEach(this::fireToolStart);
            var execCtx = new ToolExecutionContext(
                currentRunId, turnCount, permissionMode.toToolsMode(),
                new DefaultPermissionPolicy(),
                approvalHandler,
                (p, rid, prid, tn, t) -> {
                    events.record(p, rid, prid, tn, t);
                },
                internalToolRouter, approvalCache, control);
            var batchResult = toolCallExecutor.executeBatch(calls, execCtx);
            for (ToolExecutionResult result : batchResult.results()) {
                fireToolEnd(result);
            }
            // P1-G2：结果未知/部分执行不是"确定失败"——注入结构化安全警告（ephemeral）
            for (ToolExecutionResult result : batchResult.results()) {
                var certainty = result.effectCertainty();
                if (certainty == com.clawkit.tools.action.EffectCertainty.EFFECT_UNKNOWN) {
                    ephemeralContext.runtime().add(Message.system(
                        "[Runtime] 工具 " + result.toolName() + " 的执行结果未知（"
                        + (result.failureClass() != null ? result.failureClass() : "UNCLASSIFIED")
                        + "）。副作用可能已发生、可能未发生、也可能仍在进行。"
                        + "不要假设该调用已失败或已成功；只允许重新采证（读取状态确认），"
                        + "不得自动重复执行同一动作。"));
                } else if (certainty == com.clawkit.tools.action.EffectCertainty.PARTIAL_EFFECT) {
                    ephemeralContext.runtime().add(Message.system(
                        "[Runtime] 工具 " + result.toolName() + " 明确部分执行。"
                        + "必须先核实已生效的部分，再决定补偿或继续；不得直接重复执行同一动作。"));
                }
            }
            for (Message msg : batchResult.toolResultMessages()) {
                contextHistory.add(msg);
                if ("todo_write".equals(
                    calls.stream().filter(c -> c.id().equals(msg.toolCallId()))
                        .findFirst().map(ToolCall::name).orElse(""))) {
                    workspaceState.writeTodo(
                        calls.stream().filter(c -> c.id().equals(msg.toolCallId()))
                            .findFirst().map(ToolCall::arguments).orElse(null));
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
            session.replace(filterPersistable(contextHistory));
        } // end while
        } catch (com.clawkit.tools.control.ExecutionHaltedException halted) {
            // P1-G1：控制面停止（取消/deadline/预算）不是普通错误
            switch (halted.reason()) {
                case CANCELLED -> {
                    interrupted = false;
                    fireState(AgentState.INTERRUPTED, turnCount);
                    log.info("[Engine] 控制面取消，停止发起新调用");
                    completeRun(RunStatus.INTERRUPTED, null, null);
                    return "[A-001] 已被用户中断。";
                }
                case DEADLINE_EXCEEDED -> {
                    log.warn("[Engine] 超过 run deadline: {}", halted.getMessage());
                    completeRun(RunStatus.DEADLINE_EXCEEDED, "A-006", halted.getMessage());
                    return "[A-006] 运行超过 deadline，已停止发起新的模型和工具调用。";
                }
                case BUDGET_EXHAUSTED -> {
                    log.warn("[Engine] token 预算耗尽: {}", halted.getMessage());
                    completeRun(RunStatus.BUDGET_EXHAUSTED, "A-007", halted.getMessage());
                    return "[A-007] token 预算已耗尽，已停止发起新的模型调用。";
                }
            }
            throw new IllegalStateException("unreachable halt reason", halted);
        } catch (Exception e) {
            log.error("[Engine] 未捕获异常，强制发送 RunCompleted", e);
            completeRun(RunStatus.UNKNOWN_ERROR, "UNEXPECTED",
                e.getMessage() != null ? e.getMessage() : "Unexpected error");
            throw e;
        } finally {
            if (permissionMode != PermissionMode.PLAN) {
                try {
                    // 取消后不再发起记忆提取的模型调用；预算/deadline 停止也在此兜底
                    if (!control.isCancelled()) {
                        var memoryResult = memoryHooks.afterRun(new MemoryHooks.MemoryExtractionRequest(
                            session.messages(), turnCount, memoryScope(turnCount), false, 3));
                        if (memoryResult.saved() > 0) setMemoryIndex(memoryHooks.memoryIndex());
                    }
                } catch (com.clawkit.tools.control.ExecutionHaltedException haltedInFinally) {
                    log.info("[Engine] 记忆提取被控制面停止: {}", haltedInFinally.reason());
                }
            }
            completeRun(RunStatus.UNKNOWN_ERROR, null,
                "No RunCompleted emitted (UNEXPECTED)");
            interrupted = false;
            if (currentControl == control) {
                currentControl = null;
            }
        }
    }

    private boolean allReadOnly(List<ToolCall> calls) {
        return calls.stream().allMatch(c -> registry.isReadOnly(c.name()));
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
        var control = this.currentControl;
        if (control != null) {
            control.cancel(); // 级联取消 Provider / Tool / SubAgent / Plan
        }
        log.info("[Engine] 收到中断信号");
    }

    @Override
    public void clearSession() {
        if (memoryHooks.available() && permissionMode != PermissionMode.PLAN && !session.isEmpty()) {
            var result = memoryHooks.afterRun(new MemoryHooks.MemoryExtractionRequest(
                session.messages(), lastRunTurns, memoryScope(lastRunTurns), true, 5));
            if (result.saved() > 0) setMemoryIndex(memoryHooks.memoryIndex());
        }
        session.autoSave(this::isPersistable);
        session.clear();
        // Phase 3b: 清空 ephemeral 容器
        ephemeralContext.clear();
        recentCallSignatures.clear();
        approvalCache.clear();
        internalTools.clear();
        previousTodoContents = List.of();
        turnsWithoutTodoProgress = 0;
        interrupted = false;
        log.info("[Engine] 会话已清空 (含工作内存 + 子目标跟踪 + ephemeral 容器)");
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

    /** 构建供 ContextPipeline 使用的 ContextRequest。 */
    private ContextRequest buildContextRequest() {
        // 稳定 system prompt 由 ContextPipeline 的 SYSTEM fragment 唯一生成
        // sessionHistory 不再保存 system prompt；但兼容旧 session 中残留的 SYSTEM 消息
        var sessionMsgs = session.messages().stream()
            .filter(m -> !isStableSystemPrompt(m))
            .toList();
        return new ContextRequest(
            buildSystemPrompt(),
            sessionMsgs,
            List.copyOf(ephemeralContext.workspace()),
            List.copyOf(ephemeralContext.runtime()),
            List.copyOf(ephemeralContext.memory()),
            skillRuntime.activeContext(),
            registry.getAvailableTools(),
            context.policy());
    }

    /** 判断消息是否为稳定 system prompt（应唯一由 ContextPipeline 的 SYSTEM fragment 生成） */
    private boolean isStableSystemPrompt(Message m) {
        if (m.role() != com.clawkit.tools.schema.Role.SYSTEM) return false;
        String content = m.content();
        if (content == null) return false;
        // 匹配当前稳定 prompt，或旧版 L1_KERNEL
        return content.startsWith("You are clawkit")
            || content.equals(buildSystemPrompt());
    }

    /** Provider 调用唯一入口。 */
    private Message callProvider(List<Message> messages, List<ToolDefinition> tools,
                                  int turn, RunPhase phase) {
        var req = ModelRequest.of(messages, tools);
        var scope = new RunScope(currentRunId, parentRunId, turn, phase, executionMode,
            activeControl());
        var resp = providerGateway.generate(req, scope);
        if (resp.hasToolCalls()) return Message.assistantWithTools(resp.toolCalls());
        return Message.assistant(resp.content() != null ? resp.content() : "");
    }

    // ── P1-G1：run 级 ExecutionControl ───────────────────────────────

    /** 当前 run 的控制句柄；run 外调用返回 none()。 */
    private com.clawkit.tools.control.ExecutionControl activeControl() {
        var control = currentControl;
        return control != null ? control : com.clawkit.tools.control.ExecutionControl.none();
    }

    /** 创建本次 run 的控制根：SubAgent 从父控制派生（共享预算 + 级联取消）。 */
    private com.clawkit.reliability.CancellationTree createRunControl() {
        var parent = parentControl;
        if (parent != null) {
            return com.clawkit.reliability.CancellationTree.childOf(parent);
        }
        java.time.Instant deadline = runDeadline != null
            ? java.time.Instant.now().plus(runDeadline) : null;
        com.clawkit.tools.control.TokenBudget budget = runTokenBudget != null
            ? com.clawkit.reliability.BudgetLedger.of(runTokenBudget) : null;
        com.clawkit.tools.control.WorkBudget workBudget =
            runProviderCallBudget != null || runToolCallBudget != null
                ? com.clawkit.reliability.WorkBudgetLedger.of(
                    runProviderCallBudget != null ? runProviderCallBudget : Long.MAX_VALUE,
                    runToolCallBudget != null ? runToolCallBudget : Long.MAX_VALUE)
                : null;
        return com.clawkit.reliability.CancellationTree.root(deadline, budget, workBudget);
    }

    /**
     * P1-G4：装配 Side Effect Gate。
     * Reliability Journal 位于 workDir/.clawkit/reliability；journal 不可写时
     * store 构造失败，副作用能力 fail closed（引擎无法启动）。
     * attempt 迁移事件投影到 RunRecorder；投影失败不影响控制面。
     */
    private com.clawkit.reliability.gate.SideEffectGate createSideEffectGate(
            String workDir, com.clawkit.engine.AgentRuntimeDependencies runtimeDeps) {
        var store = new com.clawkit.reliability.attempt.FileActionAttemptStore(
            Path.of(workDir, ".clawkit", "reliability"));
        var coordinator = new com.clawkit.reliability.attempt.ActionAttemptCoordinator(
            store,
            com.clawkit.reliability.attempt.ActionAttemptCoordinator.AttemptPolicy.defaults(),
            (attempt, reason) -> {
                String trimmed = reason != null && reason.length() > 160
                    ? reason.substring(0, 160) : reason;
                events.record(new com.clawkit.observability.AttemptTransitionPayload(
                        attempt.attemptId(), attempt.descriptor().actionCode(),
                        attempt.targetKey(), attempt.state().name(),
                        attempt.purpose(), attempt.relatedAttemptId(), trimmed),
                    attempt.runId() != null ? attempt.runId() : currentRunId,
                    parentRunId, null, Instant.now());
            });
        if (runtimeDeps == null) {
            return new com.clawkit.reliability.gate.SideEffectGate(coordinator);
        }
        var launcher = new VerificationRunLauncher(runtimeDeps, workDir);
        return new com.clawkit.reliability.gate.SideEffectGate(coordinator, attempt -> {
            var result = launcher.verify(attempt);
            String evidence = result.deterministicDetail()
                + "; independent model review: " + result.modelConclusion();
            return new com.clawkit.reliability.gate.SideEffectGate.VerificationOutcome(
                result.deterministicPassed(), evidence);
        });
    }

    /** SubAgent：绑定父控制，子 run 级联取消并共享父预算账本。 */
    void attachParentControl(com.clawkit.tools.control.ExecutionControl parent) {
        this.parentControl = parent;
    }

    /**
     * P1-G5：进程启动恢复扫描。由 composition root 在应用启动时调用一次；
     * 崩溃遗留的 DISPATCH_INTENT 按结果未知处理并 reconcile，
     * 未派发状态确认无副作用后关闭。
     */
    public com.clawkit.reliability.gate.RecoveryScanner.RecoveryReport recoverPendingAttempts() {
        var report = com.clawkit.reliability.gate.RecoveryScanner.scan(
            sideEffectGate.coordinator().store());
        sideEffectGate.verifyPendingAttempts();
        return report;
    }

    /** 配置 run 级 deadline 与 token 预算；null 表示无限制。 */
    public void setRunLimits(java.time.Duration deadline, Long tokenBudget) {
        setRunLimits(deadline, tokenBudget, null, null);
    }

    /** Configure hard time/token/Provider-call/Tool-call budgets for each run. */
    public void setRunLimits(java.time.Duration deadline, Long tokenBudget,
                             Long providerCalls, Long toolCalls) {
        this.runDeadline = deadline;
        this.runTokenBudget = tokenBudget;
        this.runProviderCallBudget = providerCalls;
        this.runToolCallBudget = toolCalls;
        log.info("[Engine] run limits: deadline={}, tokenBudget={}, providerCalls={}, toolCalls={}",
            deadline, tokenBudget, providerCalls, toolCalls);
    }

    private RunScope memoryScope(int turn) {
        String runId = currentRunId != null && !currentRunId.isBlank()
            ? currentRunId : "memory-" + java.util.UUID.randomUUID();
        return new RunScope(runId, parentRunId, Math.max(0, turn),
            RunPhase.MEMORY_EXTRACT, executionMode);
    }

    /** 估算工具定义的 token 数 */
    private int estimateToolTokens() {
        var tools = registry.getAvailableTools();
        if (tools == null || tools.isEmpty()) return 0;
        int total = 0;
        for (var tool : tools) {
            Object schema = tool.inputSchema();
            if (schema != null) total += context.tokenizer().countTokens(schema.toString());
            total += context.tokenizer().countTokens(tool.name() != null ? tool.name() : "");
            total += context.tokenizer().countTokens(tool.description() != null ? tool.description() : "");
        }
        return total;
    }

    /** 过滤出可持久化的消息（不含 runtime 前缀的 SYSTEM 消息） */
    private List<Message> filterPersistable(List<Message> messages) {
        return messages.stream().filter(this::isPersistable).toList();
    }

    private boolean isPersistable(Message message) {
        return !ContextBudgetAnalyzer.isRuntimeSystemMessage(message)
            && !isStableSystemPrompt(message);
    }

    public void writePlanToWorkspace(String planContent) {
        workspaceState.writePlan(planContent);
    }

    // === Plan-and-Execute ===

    public void setExecutionMode(ExecutionMode mode) {
        this.executionMode = mode;
        rebuildSystemPrompt();
        log.info("[Engine] 执行模式切换为: {}", mode);
    }

    public ExecutionMode executionMode() {
        return executionMode;
    }

    public void setOnPlanReady(Predicate<ExecutionPlan> handler) {
        this.onPlanReady = handler;
    }

    // ── session management methods ──

    @Override
    public String saveSession(String name) {
        return session.save(name, this::isPersistable);
    }

    @Override
    public void loadSession(String sessionId) {
        List<Message> loaded = session.load(sessionId, this::isPersistable);
        clearSession();
        session.replace(loaded);
        log.info("[Engine] Session loaded: {} ({} messages)", sessionId, loaded.size());
    }

    @Override
    public List<SessionMeta> listSessions() {
        return session.list();
    }

    @Override
    public void deleteSession(String sessionId) {
        session.delete(sessionId);
        log.info("[Engine] Session deleted: {}", sessionId);
    }

    @Override
    public String newSession() {
        clearSession();
        log.info("[Engine] New session started");
        return "Session cleared. Ready for new conversation.";
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

    void configureAsChild(String parentRunId) {
        this.parentRunId = parentRunId;
        this.enableSubAgents = false;
    }

    public void setSessionService(SessionService sessionService) {
        this.session.setService(sessionService);
    }

    /** CLI /remember 元数据提取 — 通过 ProviderGateway */
    public String extractMemoryMetadata(String rawContent) {
        var req = ModelRequest.of(
            List.of(com.clawkit.tools.schema.Message.system(
                "Extract metadata from this user memory.\n"
                + "Output ONLY a JSON object with keys: name, description, type. No other text.\n\n"
                + "name: kebab-case identifier\ndescription: one-line summary under 120 chars\n"
                + "type: one of [user, feedback, project, reference]\n\nMemory content:\n"),
                com.clawkit.tools.schema.Message.user(rawContent)),
            List.of());
        var scope = new RunScope("cli-memory-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            null, 0, RunPhase.MEMORY_EXTRACT, executionMode);
        var resp = providerGateway.generate(req, scope);
        return resp.content() != null ? resp.content().trim() : "";
    }

    /** Explicit callers share the same persistence and deduplication path as memory_save. */
    public MemoryHooks.MemorySaveResult rememberMemory(MemoryEntry entry) {
        var result = memoryHooks.remember(java.util.Objects.requireNonNull(entry, "memory entry required"));
        setMemoryIndex(memoryHooks.memoryIndex());
        return result;
    }

    public void setProviderGateway(ProviderGateway gateway) {
        this.providerGateway = java.util.Objects.requireNonNull(gateway, "gateway required");
        this.subAgentRunner.setGateway(this.providerGateway);
        this.planCoordinator = createPlanCoordinator();
    }

    private PlanRunCoordinator createPlanCoordinator() {
        return new PlanRunCoordinator(providerGateway, registry, toolCallExecutor,
            workspaceState, events, workDir);
    }

    public void setWorkspaceRules(String rules) {
        this.l2WorkspaceRules = rules != null ? rules : "";
        rebuildSystemPrompt();
        log.info("[Engine] 工作区守则已加载 ({} chars)", l2WorkspaceRules.length());
    }

    public void rebuildSkillCatalog() {
        skillRuntime.rebuildCatalog();
        this.skillCatalog = skillRuntime.catalog();
        log.info("[Engine] 技能目录已加载: {} skills", skillCatalog.entries().size());
    }

    public SkillRuntime.SkillLoadResult loadSkill(String name) { return skillRuntime.load(name); }

    public SkillRuntime.SkillUnloadResult unloadSkill(String name) { return skillRuntime.unload(name); }

    public boolean hasSkillLoaded(String name) {
        return skillRuntime.isLoaded(name);
    }

    public void setMemoryIndex(String memoryIndex) {
        this.l5MemoryIndex = memoryIndex != null ? memoryIndex : "";
        rebuildSystemPrompt();
        log.info("[Engine] 记忆索引已更新{}", session.isEmpty() ? "" : " (system prompt rebuilt)");
    }

    public void setOnThinkingBegin(Runnable callback) {
        events.setThinkingBegin(callback);
    }

    public void onReasoning(Consumer<String> callback) {
        events.onReasoning(callback);
    }

    public void onThinkingToken(Consumer<String> callback) {
        events.onThinkingToken(callback);
    }

    public void onSubAgentSpawn(Consumer<SubAgentSpawnEvent> listener) {
        events.onSubAgentStarted(listener);
    }

    public void onSubAgentComplete(Consumer<SubAgentCompleteEvent> listener) {
        events.onSubAgentCompleted(listener);
    }

    public void onToolStart(Consumer<ToolStartEvent> listener) {
        events.onToolStarted(listener);
    }

    public void onToolEnd(Consumer<ToolEndEvent> listener) {
        events.onToolCompleted(listener);
    }

    private void fireToolStart(ToolCall call) {
        String summary = buildArgSummary(call);
        events.toolStarted(new ToolStartEvent(call.name(), summary));
    }

    private void fireToolEnd(ToolExecutionResult result) {
        boolean success = result.success();
        String output = result.output() != null ? result.output() : "";
        String detail = success
            ? result.outputBytes() + " bytes"
            : output.substring(0, Math.min(60, output.length()));
        events.toolCompleted(new ToolEndEvent(result.toolName(), success, detail));
    }

    // ── RunEvent 观测 ───────────────────────────────────────────────

    /** 注册运行事件 recorder（如 FileRunRecorder） */
    public void addRecorder(RunRecorder recorder) {
        events.addRecorder(recorder);
    }

    /** 移除运行事件 recorder */
    public void removeRecorder(RunRecorder recorder) {
        events.removeRecorder(recorder);
    }

    /** 分发事件给所有 recorder（吞异常，不中断主循环） */
    private void fireEvent(RunEventPayload payload, Integer turnNumber) {
        events.record(payload, currentRunId, parentRunId, turnNumber, Instant.now());
    }

    private void completeRun(RunStatus status, String errorCode, String message) {
        events.complete(status, errorCode, message, currentRunId, parentRunId);
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
        events.onState(listener);
    }

    public void removeOnStateChangeListener(Consumer<AgentStateEvent> listener) {
        events.removeState(listener);
    }

    @Override
    public void setOnToken(Consumer<String> callback) {
        events.setToken(callback);
    }

    @Override
    public void addOnTokenListener(Consumer<String> listener) {
        events.addToken(listener);
    }

    @Override
    public void removeOnTokenListener(Consumer<String> listener) {
        events.removeToken(listener);
    }

    private void fireState(AgentState state, int turnCount, Map<String, Object> metadata) {
        events.state(state, turnCount, metadata);
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
        return context.contextWindow();
    }

    public int getEstimatedTokens() {
        return context.estimateTokens(session.messages());
    }

    public MaskedContext getLastMaskedContext() {
        return lastMaskedContext;
    }

    public ContextBudgetReport getContextBudgetReport() {
        int toolDefTokens = estimateToolTokens();
        return context.report(session.messages(), toolDefTokens);
    }

    @Override
    public int getMessageCount() {
        return session.size();
    }

    @Override
    public Map<String, Integer> getTokenBreakdown() {
        int systemTokens = 0, userTokens = 0, assistantTokens = 0, toolTokens = 0;
        for (Message m : session.messages()) {
            int t = context.estimateTokens(List.of(m));
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
        EngineContextCoordinator.CompactionStatus c = context.lastCompaction();
        if (c == null || c.beforeTokens() == 0) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(c.beforeTokens()).append("->").append(c.afterTokens()).append(" tokens");
        return sb.toString();
    }

    /** 手动 /compact — 通过 ContextPipeline 统一入口 */
    public String compactSession() {
        if (session.isEmpty()) {
            return "session is empty, nothing to compact.";
        }
        CompactionResult cr = context.compact(new ArrayList<>(session.messages()), 0, lastRunTurns);
        List<Message> clean = filterPersistable(cr.messages());
        session.replace(clean);
        recentCallSignatures.clear();
        int before = cr.beforeMessages();
        int after = cr.afterMessages();
        int beforeTokens = cr.beforeReport() != null ? cr.beforeReport().totalTokens() : 0;
        int afterTokens = cr.afterReport() != null ? cr.afterReport().totalTokens() : 0;
        int reduction = beforeTokens > 0 ? (int) (100.0 * (beforeTokens - afterTokens) / beforeTokens) : 0;
        log.info("[Engine] 手动压缩: {}→{} msgs, {}→{} tokens ({}%)",
            before, after, beforeTokens, afterTokens, reduction);
        return String.format("compacted: %d→%d msgs, %s→%s tokens (%d%% reduced)",
            before, after, formatTokenCount(beforeTokens), formatTokenCount(afterTokens), reduction);
    }

    private String summarizeForCompact(java.util.List<Message> messages) {
        Message result = callProvider(messages, EMPTY_TOOLS, 0, RunPhase.COMPACT);
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
