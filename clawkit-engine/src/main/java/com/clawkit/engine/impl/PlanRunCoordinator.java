package com.clawkit.engine.impl;

import com.clawkit.context.PlannerPrompt;
import com.clawkit.engine.AgentState;
import com.clawkit.engine.ApprovalHandler;
import com.clawkit.engine.ExecutionMode;
import com.clawkit.engine.PermissionMode;
import com.clawkit.engine.ProviderGateway;
import com.clawkit.engine.RunPhase;
import com.clawkit.engine.RunScope;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.observability.ObservabilityRedactor;
import com.clawkit.observability.RunStartedPayload;
import com.clawkit.observability.RunStatus;
import com.clawkit.provider.LLMException;
import com.clawkit.provider.ModelRequest;
import com.clawkit.tools.Registry;
import com.clawkit.tools.Result;
import com.clawkit.tools.schema.ExecutionPlan;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.PlanStatus;
import com.clawkit.tools.schema.Task;
import com.clawkit.tools.schema.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns one complete Plan-and-Execute run. */
final class PlanRunCoordinator {
    private static final Logger log = LoggerFactory.getLogger(PlanRunCoordinator.class);
    private final ProviderGateway gateway;
    private final PlanExecutor executor;
    private final WorkspaceStateStore workspace;
    private final EngineEventHub events;
    private final String workDir;

    PlanRunCoordinator(ProviderGateway gateway, Registry registry,
                       ToolCallExecutor toolCallExecutor, WorkspaceStateStore workspace,
                       EngineEventHub events, String workDir) {
        this.gateway = gateway;
        this.executor = new PlanExecutor(gateway, registry, toolCallExecutor);
        this.workspace = workspace;
        this.events = events;
        this.workDir = workDir;
    }

    String run(String prompt, String runId, String parentRunId,
               PermissionMode permission, ThinkingMode thinking,
               ApprovalHandler approval, Predicate<ExecutionPlan> confirmation,
               com.clawkit.tools.control.ExecutionControl control) {
        if (control == null) control = com.clawkit.tools.control.ExecutionControl.none();
        events.resetRun();
        events.record(new RunStartedPayload(ObservabilityRedactor.summarizeTask(prompt),
            workDir, "plan-exec", permission.name(), thinking.name(),
            ExecutionMode.PLAN_EXECUTE.name()), runId, parentRunId, null, Instant.now());
        events.state(AgentState.PLANNING, 0, Map.of());
        try {
            String existingPlan = workspace.read(".clawkit/plan.md");
            String existingTodo = workspace.read(".clawkit/todo.md");
            String planningPrompt = PlannerPrompt.buildInitialPrompt(
                prompt, existingPlan, existingTodo);

            String planJson;
            try {
                var response = gateway.generate(ModelRequest.of(List.of(
                    Message.system("You are a task planner. Output pure JSON only."),
                    Message.user(planningPrompt)), List.of()),
                    new RunScope(runId, parentRunId, 0,
                        RunPhase.TWO_STAGE_PLAN, ExecutionMode.PLAN_EXECUTE, control));
                planJson = response.content() != null ? response.content() : "";
            } catch (LLMException e) {
                events.state(AgentState.ERROR, 0, Map.of("error", e.getMessage()));
                complete(RunStatus.PLANNING_ERROR, "A-002", e.getMessage(), runId, parentRunId);
                return ProviderFailureMessage.format(e);
            }
            if (planJson.isBlank()) {
                complete(RunStatus.PLAN_PARSE_ERROR, "P-001", "LLM 未返回计划内容", runId, parentRunId);
                return "[P-001] LLM 未返回计划内容";
            }

            Result<ExecutionPlan> parsed = new PlanParser().parse(planJson);
            if (parsed instanceof Result.Err<ExecutionPlan> error) {
                complete(RunStatus.PLAN_PARSE_ERROR, "P-00x", error.error().message(), runId, parentRunId);
                return "[P-00x] 计划解析失败: " + error.error().message();
            }
            ExecutionPlan plan = ((Result.Ok<ExecutionPlan>) parsed).data();
            workspace.writePlan(formatInitialPlan(plan));

            if (confirmation != null && !confirmation.test(plan)) {
                plan.setStatus(PlanStatus.CANCELLED);
                complete(RunStatus.PLAN_REJECTED, null, "用户取消计划", runId, parentRunId);
                return "计划已取消。用 /auto 或 /ask 切换到执行模式手动执行。";
            }

            events.state(AgentState.EXECUTING, 0, Map.of("taskCount", plan.taskCount()));
            var context = new PlanExecutionContext(permission.toToolsMode(), approval,
                events.recorder(), runId, control);
            ExecutionPlan completed = executor.execute(plan, context);
            workspace.writePlan(formatCompletedPlan(completed));
            events.state(AgentState.REPLYING, completed.taskCount(), Map.of());
            if (completed.getStatus() == PlanStatus.COMPLETED) {
                complete(RunStatus.COMPLETED, null, null, runId, parentRunId);
            } else {
                complete(RunStatus.EXECUTION_FAILED, "P-004",
                    "计划未完成全部任务", runId, parentRunId);
            }
            return summary(completed);
        } catch (com.clawkit.tools.control.ExecutionHaltedException halted) {
            // P1-G1：控制面停止（取消/deadline/预算）映射为结构化终态
            switch (halted.reason()) {
                case CANCELLED -> {
                    complete(RunStatus.INTERRUPTED, null, null, runId, parentRunId);
                    return "[A-001] 计划执行已被用户中断。";
                }
                case DEADLINE_EXCEEDED -> {
                    complete(RunStatus.DEADLINE_EXCEEDED, "A-006", halted.getMessage(),
                        runId, parentRunId);
                    return "[A-006] 计划执行超过 deadline，已停止。";
                }
                case BUDGET_EXHAUSTED -> {
                    complete(RunStatus.BUDGET_EXHAUSTED, "A-007", halted.getMessage(),
                        runId, parentRunId);
                    return "[A-007] token 预算耗尽，计划执行已停止。";
                }
            }
            throw new IllegalStateException("unreachable halt reason", halted);
        } catch (Exception e) {
            log.error("[PlanExecute] unhandled failure", e);
            complete(RunStatus.UNKNOWN_ERROR, "UNEXPECTED",
                e.getMessage() != null ? e.getMessage() : "Unexpected error", runId, parentRunId);
            throw e;
        } finally {
            complete(RunStatus.UNKNOWN_ERROR, null, "No RunCompleted emitted", runId, parentRunId);
        }
    }

    private void complete(RunStatus status, String code, String message,
                          String runId, String parentRunId) {
        events.complete(status, code, message, runId, parentRunId);
    }

    private static String formatInitialPlan(ExecutionPlan plan) {
        StringBuilder md = new StringBuilder("# Plan: ").append(plan.getGoal()).append("\n\n");
        List<List<String>> levels = PlanParser.computeLevels(plan.getTasks());
        for (int i = 0; i < levels.size(); i++) {
            md.append("## Level ").append(i).append("\n\n");
            for (String id : levels.get(i)) {
                Task task = plan.getTask(id);
                if (task == null) continue;
                md.append("- **").append(id).append("** [").append(task.getTaskType())
                    .append("]: ").append(task.getDescription()).append('\n');
                if (!task.getDependencies().isEmpty()) {
                    md.append("  - depends on: ")
                        .append(String.join(", ", task.getDependencies())).append('\n');
                }
            }
            md.append('\n');
        }
        return md.toString();
    }

    private static String formatCompletedPlan(ExecutionPlan plan) {
        StringBuilder md = new StringBuilder("# Execution Plan\n\nGoal: ")
            .append(plan.getGoal()).append("\n\nStatus: ").append(plan.getStatus()).append("\n\n");
        for (String id : plan.getExecutionOrder()) {
            Task task = plan.getTask(id);
            if (task == null) continue;
            md.append("### ").append(id).append(": ").append(task.getDescription()).append('\n')
                .append("- Type: ").append(task.getTaskType()).append('\n')
                .append("- Status: ").append(task.getStatus()).append('\n');
            if (task.getResult() != null && !task.getResult().isBlank()) {
                String result = task.getResult();
                md.append("- Result: ").append(result.length() > 200
                    ? result.substring(0, 200) + "..." : result).append('\n');
            }
            if (task.getErrorMessage() != null) {
                md.append("- Error: ").append(task.getErrorMessage()).append('\n');
            }
            md.append('\n');
        }
        return md.append("Summary: ")
            .append(plan.getSummary() != null ? plan.getSummary() : "").append('\n').toString();
    }

    private static String summary(ExecutionPlan plan) {
        long completed = count(plan, TaskStatus.COMPLETED);
        long failed = count(plan, TaskStatus.FAILED);
        long skipped = count(plan, TaskStatus.SKIPPED);
        return "## 执行完成\n\n目标: " + plan.getGoal() + "\n\n结果: "
            + completed + "/" + plan.taskCount() + " 成功"
            + (failed > 0 ? ", " + failed + " 失败" : "")
            + (skipped > 0 ? ", " + skipped + " 跳过" : "") + "\n\n"
            + (plan.getSummary() != null ? plan.getSummary() : "");
    }

    private static long count(ExecutionPlan plan, TaskStatus status) {
        return plan.getTasks().values().stream().filter(t -> t.getStatus() == status).count();
    }
}
