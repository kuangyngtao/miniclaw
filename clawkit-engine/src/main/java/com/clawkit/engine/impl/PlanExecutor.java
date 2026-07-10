package com.clawkit.engine.impl;

import com.clawkit.provider.LLMProvider;
import com.clawkit.tools.Registry;
import com.clawkit.tools.schema.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plan-and-Execute 核心调度器。
 * 按层级执行任务 DAG：同级虚拟线程并行，失败走 ReAct 分析 → RETRY/REPLAN/SKIP。
 */
public class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    private static final int MAX_TURNS_EXPLORE = 15;
    private static final int MAX_TURNS_MODIFY  = 25;
    private static final int MAX_TURNS_VERIFY  = 10;
    private static final int MAX_RETRIES       = 2;
    private static final int MAX_SLOT_CHARS    = 8000;

    private final LLMProvider provider;
    private final Registry registry;

    public PlanExecutor(LLMProvider provider, Registry registry, String workDir) {
        this.provider = provider;
        this.registry = registry;
    }

    /**
     * 主入口：执行完整计划。所有 Task 的 status/result/errorMessage 被原地更新。
     */
    public ExecutionPlan execute(ExecutionPlan plan) {
        plan.setStatus(PlanStatus.RUNNING);
        Map<String, String> slotValues = new ConcurrentHashMap<>();
        List<List<String>> levels = PlanParser.computeLevels(plan.getTasks());
        AtomicBoolean cancelled = new AtomicBoolean(false);

        log.info("[PlanExecutor] 开始执行: {} tasks, {} levels",
            plan.taskCount(), levels.size());

        for (int levelIdx = 0; levelIdx < levels.size(); levelIdx++) {
            if (cancelled.get()) break;

            List<String> level = levels.get(levelIdx);
            log.info("[PlanExecutor] Level {}/{}: {} tasks",
                levelIdx + 1, levels.size(), level.size());

            boolean shouldCancel = executeLevel(level, plan.getTasks(), slotValues, cancelled);

            if (shouldCancel) {
                skipRemainingTasks(plan, levelIdx + 1, levels);
                break;
            }
        }

        // 确定计划最终状态
        boolean allCompleted = plan.getTasks().values().stream()
            .allMatch(t -> t.getStatus() == TaskStatus.COMPLETED);
        boolean anyFailed = plan.getTasks().values().stream()
            .anyMatch(t -> t.getStatus() == TaskStatus.FAILED);

        if (allCompleted) {
            plan.setStatus(PlanStatus.COMPLETED);
        } else if (anyFailed) {
            plan.setStatus(PlanStatus.FAILED);
        }

        buildSummary(plan);
        return plan;
    }

    /**
     * 执行一个拓扑层级（同级任务并行）。
     * @return true 表示需要取消剩余层级（有 REPLAN 请求）
     */
    private boolean executeLevel(List<String> taskIds, Map<String, Task> tasks,
                                  Map<String, String> slotValues, AtomicBoolean cancelled) {
        CountDownLatch latch = new CountDownLatch(taskIds.size());
        AtomicInteger replanCount = new AtomicInteger(0);

        for (String taskId : taskIds) {
            Thread.ofVirtual().start(() -> {
                try {
                    if (cancelled.get()) return;
                    Task task = tasks.get(taskId);
                    boolean needReplan = executeSingleTask(task, slotValues);
                    if (needReplan) {
                        replanCount.incrementAndGet();
                        cancelled.set(true);
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
            cancelled.set(true);
            log.warn("[PlanExecutor] 执行被中断");
        }

        return replanCount.get() > 0;
    }

    /**
     * 执行单个任务（带重试和失败分析）。
     * @return true 表示请求 REPLAN
     */
    private boolean executeSingleTask(Task task, Map<String, String> slotValues) {
        log.info("[PlanExecutor]   -> {}: {} [{}]", task.getId(), task.getDescription(), task.getTaskType());
        task.setStatus(TaskStatus.RUNNING);
        task.setStartTime(Instant.now());

        String resolvedPrompt = resolveSlots(task.getDescription(), task.getInputSlots(), slotValues);

        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            try {
                String result = runSubAgentForTask(task, resolvedPrompt);
                task.setResult(result);
                task.setStatus(TaskStatus.COMPLETED);
                task.setEndTime(Instant.now());

                if (task.getOutputSlot() != null && !task.getOutputSlot().isBlank()) {
                    String truncated = result.length() > MAX_SLOT_CHARS
                        ? result.substring(0, MAX_SLOT_CHARS) + "...(truncated)"
                        : result;
                    slotValues.put(task.getOutputSlot(), truncated);
                }

                log.info("[PlanExecutor]   <- {} 完成 (尝试{})", task.getId(), attempt);
                return false;

            } catch (Exception e) {
                log.warn("[PlanExecutor]   <- {} 失败 (尝试{}/{}): {}",
                    task.getId(), attempt, MAX_RETRIES + 1, e.getMessage());

                if (attempt <= MAX_RETRIES) {
                    AnalysisResult analysis = analyzeFailure(task, e.getMessage(), attempt);
                    switch (analysis.action()) {
                        case "RETRY" -> {
                            if (analysis.feedback() != null && !analysis.feedback().isBlank()) {
                                resolvedPrompt = resolvedPrompt
                                    + "\n\n[Reviewer feedback from previous attempt]: "
                                    + analysis.feedback();
                            }
                        }
                        case "REPLAN" -> {
                            task.setErrorMessage(e.getMessage() + " → REPLAN");
                            task.setStatus(TaskStatus.FAILED);
                            task.setEndTime(Instant.now());
                            return true;
                        }
                        case "SKIP" -> {
                            task.setErrorMessage(e.getMessage() + " → SKIP");
                            task.setStatus(TaskStatus.SKIPPED);
                            task.setEndTime(Instant.now());
                            return false;
                        }
                    }
                } else {
                    task.setErrorMessage(e.getMessage() + " [MAX_RETRIES]");
                    task.setStatus(TaskStatus.FAILED);
                    task.setEndTime(Instant.now());
                }
            }
        }

        return false;
    }

    /**
     * 为任务启动轻量 ReAct 子循环。
     * 比 spawnSubAgent 更轻——无 session/技能/记忆。
     */
    private String runSubAgentForTask(Task task, String prompt) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(buildTaskSystemPrompt(task.getTaskType())));
        messages.add(Message.user(prompt));

        List<ToolDefinition> tools = getToolsForTaskType(task.getTaskType());
        int maxTurns = switch (task.getTaskType()) {
            case EXPLORE -> MAX_TURNS_EXPLORE;
            case MODIFY  -> MAX_TURNS_MODIFY;
            case VERIFY  -> MAX_TURNS_VERIFY;
        };

        for (int turn = 0; turn < maxTurns; turn++) {
            Message response = provider.generate(messages, tools);
            messages.add(response);

            if (response.toolCalls() == null || response.toolCalls().isEmpty()) {
                return response.content() != null ? response.content() : "(no output)";
            }

            for (ToolCall call : response.toolCalls()) {
                log.debug("  [{}] tool: {}", task.getId(), call.name());
                ToolResult result = registry.execute(call);
                messages.add(Message.toolResult(call.id(), result.output()));
            }
        }

        return "(max turns reached: " + maxTurns + ")";
    }

    /**
     * 失败分析：轻量 LLM 调用，决定下一步动作。
     */
    /**
     * Reviewer：轻量 ReAct 循环，用工具诊断失败根因 → 输出决策 + 反馈。
     */
    private AnalysisResult analyzeFailure(Task task, String errorMessage, int attempt) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("""
            You are a Code Review agent. A sub-task has failed. Your job is to diagnose
            why it failed and decide what to do next.

            You have access to read-only tools (glob, grep, read) and bash. Use them to
            inspect the current state of the codebase and understand what went wrong.

            When you are done diagnosing, output your decision in this format:
            DECISION: <RETRY|REPLAN|SKIP>
            Feedback: <specific guidance for the worker if RETRY>"""));

        String prompt = """
            Diagnose this task failure:

            Task: %s
            Type: %s
            Error: %s
            Attempt: %d / %d

            Investigate the current state, then output your decision."""
            .formatted(task.getDescription(), task.getTaskType(), errorMessage,
                attempt, MAX_RETRIES + 1);
        messages.add(Message.user(prompt));

        List<ToolDefinition> tools = getReviewerTools();

        for (int turn = 0; turn < MAX_REVIEWER_TURNS; turn++) {
            Message response;
            try {
                response = provider.generate(messages, tools);
            } catch (Exception e) {
                log.warn("[PlanExecutor] Reviewer LLM 异常, 默认 RETRY: {}", e.getMessage());
                return new AnalysisResult("RETRY", e.getMessage());
            }
            messages.add(response);

            if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
                for (ToolCall call : response.toolCalls()) {
                    log.debug("  [Reviewer] tool: {}", call.name());
                    ToolResult result = registry.execute(call);
                    messages.add(Message.toolResult(call.id(), result.output()));
                }
                continue;
            }

            return parseDecision(response.content());
        }

        return new AnalysisResult("RETRY", "Reviewer max turns reached");
    }

    /** 解析 Reviewer 输出的 DECISION 行。 */
    private AnalysisResult parseDecision(String content) {
        if (content == null) return new AnalysisResult("RETRY", null);
        String upper = content.toUpperCase();
        String action = "RETRY";
        if (upper.contains("REPLAN")) action = "REPLAN";
        if (upper.contains("SKIP"))   action = "SKIP";

        // 提取 Feedback 行
        String feedback = null;
        int fbIdx = content.indexOf("Feedback:");
        if (fbIdx >= 0) {
            int end = content.indexOf('\n', fbIdx);
            feedback = end >= 0
                ? content.substring(fbIdx + 9, end).strip()
                : content.substring(fbIdx + 9).strip();
        }
        return new AnalysisResult(action, feedback);
    }

    /** Reviewer 可用工具：读 + bash，不能写。 */
    private List<ToolDefinition> getReviewerTools() {
        return registry.getAvailableTools().stream()
            .filter(t -> registry.isReadOnly(t.name()) || "bash".equals(t.name()))
            .toList();
    }

    /** 根据任务类型过滤可用工具集。 */
    private List<ToolDefinition> getToolsForTaskType(TaskType type) {
        return switch (type) {
            case EXPLORE -> registry.getAvailableTools().stream()
                .filter(t -> registry.isReadOnly(t.name()))
                .toList();
            case MODIFY -> registry.getAvailableTools();
            case VERIFY -> registry.getAvailableTools().stream()
                .filter(t -> "bash".equals(t.name()))
                .toList();
        };
    }

    /** 按任务类型构建专属系统提示词。 */
    private String buildTaskSystemPrompt(TaskType type) {
        return switch (type) {
            case EXPLORE -> """
                You are an Exploration agent. Your job is to find and understand.
                Strategies: broad searches first (glob/grep), then narrow down, read
                the most relevant files. Summarize findings clearly with file paths
                and line numbers. Do NOT modify any files.""";
            case MODIFY -> """
                You are an Implementation agent. Your job is to make precise code changes.
                Strategies: read before edit, make one change at a time, verify each
                change after making it. Prefer edit over write for existing files.
                Follow existing code style and naming conventions.""";
            case VERIFY -> """
                You are a Verification agent. Your job is to confirm correctness.
                Strategies: run compilation commands, run relevant tests, grep for
                remaining references or issues. Report pass/fail with specific evidence
                (command output, file paths, line numbers).""";
        };
    }

    /** 解析 {{slotName}} 占位符为槽位值。 */
    private String resolveSlots(String description, Map<String, String> inputSlots,
                                 Map<String, String> slotValues) {
        if (inputSlots == null || inputSlots.isEmpty()) return description;
        String result = description;
        for (var entry : inputSlots.entrySet()) {
            String slotName = entry.getKey();
            String value = slotValues.getOrDefault(slotName, "[slot:" + slotName + " not resolved]");
            result = result.replace("{{" + slotName + "}}", value);
        }
        return result;
    }

    /** 标记剩余层级的所有 PENDING 任务为 SKIPPED。 */
    private void skipRemainingTasks(ExecutionPlan plan, int fromLevel, List<List<String>> levels) {
        for (int i = fromLevel; i < levels.size(); i++) {
            for (String taskId : levels.get(i)) {
                Task t = plan.getTask(taskId);
                if (t != null && t.getStatus() == TaskStatus.PENDING) {
                    t.setStatus(TaskStatus.SKIPPED);
                    t.setErrorMessage("上游任务失败，跳过");
                }
            }
        }
    }

    private void buildSummary(ExecutionPlan plan) {
        long total = plan.taskCount();
        long completed = plan.getTasks().values().stream()
            .filter(t -> t.getStatus() == TaskStatus.COMPLETED).count();
        long failed = plan.getTasks().values().stream()
            .filter(t -> t.getStatus() == TaskStatus.FAILED).count();
        long skipped = plan.getTasks().values().stream()
            .filter(t -> t.getStatus() == TaskStatus.SKIPPED).count();

        plan.setSummary(String.format(
            "%d/%d 成功, %d 失败, %d 跳过", completed, total, failed, skipped));
    }

    private static final int MAX_REVIEWER_TURNS = 5;

    private record AnalysisResult(String action, String feedback) {}
}
