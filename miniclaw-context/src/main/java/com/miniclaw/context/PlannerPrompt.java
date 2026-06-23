package com.miniclaw.context;

/**
 * 规划阶段提示词工厂 — 为 LLM 生成 Plan JSON 的任务模板。
 */
public final class PlannerPrompt {

    private PlannerPrompt() {}

    private static final String PLANNING_TEMPLATE = """
        You are a task planner. Break down the following goal into a DAG of tasks.

        ## Available Task Types
        - EXPLORE — read-only analysis (tools: read, glob, grep, web_fetch)
        - MODIFY — code changes (tools: read, write, edit, bash, glob, grep, web_fetch, todo_write)
        - VERIFY — validation (tools: bash for compile/test)

        ## Output Format (pure JSON, no markdown fences, no extra text)
        {
          "goal": "<original goal>",
          "tasks": {
            "<task-id>": {
              "id": "<task-id>",
              "description": "<what to do, referencing {{slotName}} for upstream results>",
              "type": "EXPLORE|MODIFY|VERIFY",
              "dependencies": ["<upstream-task-id>", ...],
              "inputSlots": {"<slotName>": "<source-task-id>"},
              "outputSlot": "<slotName>"
            }
          }
        }

        ## Rules
        1. Each task ID must be unique (e.g. "task-1", "task-2")
        2. Dependencies must reference valid task IDs; no circular dependencies
        3. First tasks have empty dependencies []
        4. Independent tasks (same dependency level) execute in parallel
        5. Keep granularity moderate: 3-10 tasks typical
        6. Start with EXPLORE tasks to understand the codebase
        7. Use outputSlot / {{slotName}} to pass results between tasks
        8. Input slots resolved at runtime: {{analysis-result}} is replaced with upstream task's output
        9. Dependencies restrict slot scope — a task can only reference slots from its direct or transitive dependencies
        """;

    /**
     * 初始规划提示词。附带 .miniclaw/plan.md 和 .miniclaw/todo.md 的内容作为续接上下文。
     */
    public static String buildInitialPrompt(String goal, String existingPlanMd, String existingTodoMd) {
        StringBuilder sb = new StringBuilder(PLANNING_TEMPLATE);

        if (existingPlanMd != null && !existingPlanMd.isBlank()) {
            sb.append("\n## Existing Plan (resume context)\n\n").append(existingPlanMd).append("\n");
        }
        if (existingTodoMd != null && !existingTodoMd.isBlank()) {
            sb.append("\n## Previous Progress\n\n").append(existingTodoMd).append("\n");
        }

        sb.append("\n## Goal\n\n").append(goal);
        return sb.toString();
    }

    /**
     * REPLAN 提示词 — 当某个任务失败后，重新规划剩余部分。
     */
    public static String buildReplanPrompt(
            String originalGoal,
            String completedTasksJson,
            String failedTaskId,
            String failureReason) {
        return """
            A task in the plan has failed. Re-plan the remaining work.

            Original goal: %s

            Completed tasks:
            %s

            Failed task: %s
            Failure reason: %s

            Output the remaining tasks as a JSON plan (same format as initial plan).
            The failed task can be retried, split, or replaced with an alternative approach.
            Reference completed work using the slots listed above.
            """.formatted(originalGoal, completedTasksJson, failedTaskId, failureReason);
    }
}
