package com.miniclaw.engine.impl;

import com.miniclaw.context.ContextManager;
import com.miniclaw.context.impl.LadderedCompactor;
import com.miniclaw.engine.AgentLoop;
import com.miniclaw.engine.PermissionMode;
import com.miniclaw.engine.ThinkingMode;
import com.miniclaw.provider.LLMException;
import com.miniclaw.provider.LLMProvider;
import com.miniclaw.tools.Registry;
import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.Role;
import com.miniclaw.tools.schema.ToolCall;
import com.miniclaw.tools.schema.ToolDefinition;
import com.miniclaw.tools.schema.ToolResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentEngine — miniclaw 的核心驱动。
 * 组装 LLMProvider + Registry，驱动 ReAct 循环。
 * 支持两种思考模式：OFF（单阶段）和 TWO_STAGE（先规划再执行）。
 */
public class AgentEngine implements AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    private String buildSystemPrompt() {
        String base = "You are miniclaw, an expert coding assistant. ";
        String prompt = switch (permissionMode) {
            case PLAN -> base
                + "You are in PLAN (read-only) mode. Only read tools (read, glob, grep) "
                + "are available. Focus on thorough code exploration and analysis. "
                + "Create a detailed plan before any changes, but do NOT attempt to call "
                + "write/edit/bash -- they are disabled.";
            case ASK -> base
                + "Write operations (write, edit, bash) require user confirmation. "
                + "Read operations (read, glob, grep) run automatically. "
                + "Before calling a write tool, briefly explain what you intend to change.";
            case AUTO -> base
                + "You have full access to all tools. Think step by step before acting.";
        };

        if (memoryIndex != null && !memoryIndex.isBlank()) {
            prompt += "\n\n## Available Memory\n\nThe following is context gathered "
                + "from previous sessions. Use it to tailor your responses.\n\n"
                + memoryIndex;
        }

        return prompt;
    }

    private static final int MAX_TURNS = 50;
    private static final int MAX_CONTEXT_TOKENS = 8000;
    private static final int DEAD_LOOP_THRESHOLD = 3;
    private static final int PROGRESS_REMINDER_INTERVAL = 10;
    private static final List<ToolDefinition> EMPTY_TOOLS = Collections.emptyList();

    private final LLMProvider provider;
    private final Registry registry;
    private final String workDir;
    private final ContextManager contextManager;
    private final List<Message> sessionHistory = new ArrayList<>();
    private final List<String> recentCallSignatures = new ArrayList<>();
    private volatile String memoryIndex;
    private volatile ThinkingMode thinkingMode;
    private volatile PermissionMode permissionMode = PermissionMode.AUTO;
    private volatile Runnable onThinkingBegin;
    private final List<Consumer<String>> onReasoningListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> onThinkingTokenListeners = new CopyOnWriteArrayList<>();
    private volatile Predicate<ToolCall> permissionHandler;
    private volatile boolean interrupted;
    private final List<Consumer<String>> onTokenListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);

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
        this.contextManager = new LadderedCompactor(this::summarizeForCompact);
        this.thinkingMode = thinkingMode;
        if (onReasoning != null) onReasoningListeners.add(onReasoning);
        if (onToken != null) onTokenListeners.add(onToken);
        this.memoryIndex = memoryIndex;
    }

    @Override
    public String run(String userPrompt) {
        log.info("[Engine] 引擎启动，锁定工作区: {}, 思考模式: {}", workDir, thinkingMode);

        if (sessionHistory.isEmpty()) {
            sessionHistory.add(Message.system(buildSystemPrompt()));
        }
        sessionHistory.add(Message.user(userPrompt));
        List<Message> contextHistory = sessionHistory;

        int turnCount = 0;

        while (true) {
            if (interrupted) {
                interrupted = false;
                log.info("[Engine] 被用户中断，退出循环");
                return "[A-001] 已被用户中断。";
            }

            turnCount++;
            log.info("========== [Turn {}] 开始 ==========", turnCount);

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
                    contextHistory.add(Message.system(warning));
                    log.warn("[Engine] 死循环检测触发: {}", last);
                    recentCallSignatures.clear();
                }
            }

            // 第2层: 进度提醒 — 每10轮注入
            if (turnCount > 1 && turnCount % PROGRESS_REMINDER_INTERVAL == 0) {
                String reminder = "[Runtime] 当前已执行 " + turnCount
                    + " 轮。请简要评估推进进度，如任务复杂可考虑拆分为子步骤。";
                contextHistory.add(Message.system(reminder));
                log.info("[Engine] 进度提醒注入: 第 {} 轮", turnCount);
            }

            // 第3层: 安全硬上限 — 50轮兜底
            if (turnCount > MAX_TURNS) {
                log.warn("[Engine] 达到安全硬上限 ({}), 强制退出", MAX_TURNS);
                return "[A-001] 达到最大迭代轮次 (" + MAX_TURNS + ")，任务可能过于复杂，请拆分为子任务";
            }

            // 上下文阶梯压缩检查
            int estimatedTokens = contextManager.estimateTokens(contextHistory);
            if (estimatedTokens > MAX_CONTEXT_TOKENS * 0.6) {
                int before = contextHistory.size();
                List<Message> compacted = contextManager.compact(contextHistory, MAX_CONTEXT_TOKENS);
                sessionHistory.clear();
                sessionHistory.addAll(compacted);
                log.info("[Engine] 上下文压缩: {} → {} 条消息 ({} tokens)",
                    before, sessionHistory.size(), estimatedTokens);
            }

            // === 两阶段慢思考：每轮先剥离工具，强制推理规划 ===
            // Phase 1 的思考内容作为 ephemeral context 注入 Phase 2 但不持久化，
            // 避免 token 浪费和内部 Trace 泄露。
            Message thinkingMsg = null;
            if (thinkingMode == ThinkingMode.TWO_STAGE && turnCount == 1) {
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
                    log.error("[Engine] 慢思考阶段1 失败: {}", e.getMessage());
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
                availableTools = registry.getAvailableTools();
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

            final Message responseMsg;
            try {
                if (!onTokenListeners.isEmpty()) {
                    responseMsg = provider.generateStream(phase2Context, availableTools,
                        token -> onTokenListeners.forEach(l -> l.accept(token)));
                } else {
                    responseMsg = provider.generate(phase2Context, availableTools);
                }
            } catch (LLMException e) {
                log.error("[Engine] LLM 调用失败 (A-002): {}", e.getMessage());
                return "[A-002] LLM 调用失败: " + e.getMessage();
            }
            contextHistory.add(responseMsg);

            if (responseMsg.content() != null && !responseMsg.content().isEmpty()) {
                log.debug("Model response: {} chars", responseMsg.content().length());
            }

            // 退出条件：没有工具调用
            if (responseMsg.toolCalls() == null || responseMsg.toolCalls().isEmpty()) {
                log.info("[Engine] 任务完成，退出循环。");
                return responseMsg.content() != null ? responseMsg.content() : "";
            }

            // Action + Observation
            List<ToolCall> calls = responseMsg.toolCalls();
            log.info("[Engine] 模型请求调用 {} 个工具, {} 模式...",
                calls.size(), allReadOnly(calls) ? "并行" : "串行");

            if (allReadOnly(calls)) {
                executeParallel(calls, contextHistory);
            } else {
                executeSequential(calls, contextHistory);
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
            Thread.ofVirtual().start(() -> {
                try {
                    results[idx] = registry.execute(call);
                    ToolResult r = results[idx];
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

        for (ToolResult result : results) {
            contextHistory.add(Message.toolResult(result.toolCallId(), result.output()));
        }
    }

    private void executeSequential(List<ToolCall> calls, List<Message> contextHistory) {
        for (ToolCall call : calls) {
            if (!registry.isReadOnly(call.name())) {
                if (permissionMode == PermissionMode.PLAN) {
                    contextHistory.add(Message.toolResult(call.id(),
                        "Tool '" + call.name() + "' is not available in PLAN mode. "
                        + "Switch to ASK or AUTO mode to execute write operations."));
                    continue;
                }
                if (permissionMode == PermissionMode.ASK && permissionHandler != null) {
                    if (!permissionHandler.test(call)) {
                        contextHistory.add(Message.toolResult(call.id(),
                            "User denied tool execution: " + call.name()));
                        continue;
                    }
                }
            }
            ToolResult result = registry.execute(call);
            log.info("  -> 执行工具: {}, 参数: {}", call.name(), call.arguments());
            if (result.isError()) {
                log.info("  -> 工具执行报错: {}", result.output());
            } else {
                log.info("  -> 工具执行成功 (返回 {} 字节)", result.output().length());
            }
            contextHistory.add(Message.toolResult(call.id(), result.output()));
        }
    }

    @Override
    public void setThinkingMode(ThinkingMode mode) {
        this.thinkingMode = mode;
        log.info("[Engine] 思考模式切换为: {}", mode);
    }

    @Override
    public void setPermissionMode(PermissionMode mode) {
        this.permissionMode = mode;
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

    @Override
    public void clearSession() {
        sessionHistory.clear();
        recentCallSignatures.clear();
        interrupted = false;
        log.info("[Engine] 会话已清空");
    }

    public void setPermissionHandler(Predicate<ToolCall> handler) {
        this.permissionHandler = handler;
    }

    public void setMemoryIndex(String memoryIndex) {
        this.memoryIndex = memoryIndex;
        if (!sessionHistory.isEmpty() && sessionHistory.get(0).role() == Role.SYSTEM) {
            sessionHistory.set(0, Message.system(buildSystemPrompt()));
        }
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

    @Override
    public boolean tryAcquire() {
        return busy.compareAndSet(false, true);
    }

    @Override
    public void release() {
        busy.set(false);
    }

    public int getEstimatedTokens() {
        return contextManager.estimateTokens(sessionHistory);
    }

    public String compactSession() {
        int before = contextManager.estimateTokens(sessionHistory);
        if (sessionHistory.isEmpty()) {
            return "session is empty, nothing to compact.";
        }
        List<Message> compacted = contextManager.compact(sessionHistory, MAX_CONTEXT_TOKENS);
        int after = contextManager.estimateTokens(compacted);
        sessionHistory.clear();
        sessionHistory.addAll(compacted);
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
