package com.clawkit.engine.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawkit.provider.LLMProvider;
import com.clawkit.tools.Result;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.schema.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanExecutorTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final List<String> executedToolNames = Collections.synchronizedList(new ArrayList<>());

    private ToolRegistry registry;
    private PlanExecutor executor;

    @BeforeEach
    void setUp() {
        executedToolNames.clear();
        registry = new ToolRegistry();
        registry.register(makeTool("glob", true));
        registry.register(makeTool("grep", true));
        registry.register(makeTool("read", true));
        registry.register(makeTool("web_fetch", true));
        registry.register(makeTool("write", false));
        registry.register(makeTool("edit", false));
        registry.register(makeTool("bash", false));
    }

    private Tool makeTool(String name, boolean readOnly) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test: " + name; }
            @Override public String inputSchema() { return "{}"; }
            @Override public boolean isReadOnly() { return readOnly; }
            @Override public Result<String> execute(String arguments) {
                executedToolNames.add(name);
                return new Result.Ok<>("ok: " + name);
            }
        };
    }

    // === 1. Linear execution order ===

    @Test
    void shouldExecuteLinearPlanInOrder() {
        ExecutionPlan plan = new ExecutionPlan("test linear");
        plan.addTask(new Task("task-1", "first task", TaskType.EXPLORE, List.of(), Map.of(), null));
        plan.addTask(new Task("task-2", "second task", TaskType.MODIFY, List.of("task-1"), Map.of(), null));
        plan.addTask(new Task("task-3", "third task", TaskType.VERIFY, List.of("task-2"), Map.of(), null));

        List<String> order = new ArrayList<>();
        for (var level : PlanParser.computeLevels(plan.getTasks())) {
            order.addAll(level);
        }
        plan.setExecutionOrder(order);

        LLMProvider provider = new LLMProvider() {
            final Map<String, Integer> taskTurns = new ConcurrentHashMap<>();
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> tools) {
                String taskId = messages.stream()
                    .filter(m -> m.content() != null && m.content().contains("first"))
                    .findFirst().map(m -> "task-1").orElse("task-1");

                int turn = taskTurns.merge(taskId, 1, Integer::sum);
                if (turn == 1) {
                    JsonNode args = mapper.createObjectNode().put("pattern", "*.java");
                    return Message.assistantWithTools(List.of(
                        new ToolCall("call_1", "glob", args)));
                }
                return Message.assistant("completed " + taskId);
            }
        };

        executor = new PlanExecutor(provider, registry, "/tmp");
        ExecutionPlan result = executor.execute(plan);

        // All tasks should complete
        assertThat(result.getStatus()).isEqualTo(PlanStatus.COMPLETED);
        assertThat(result.getTask("task-1").getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(result.getTask("task-2").getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(result.getTask("task-3").getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    // === 2. Slot passing ===

    @Test
    void shouldPassResultsViaSlots() {
        ExecutionPlan plan = new ExecutionPlan("test slots");
        plan.addTask(new Task("task-1", "find files", TaskType.EXPLORE, List.of(), Map.of(), "foundFiles"));
        plan.addTask(new Task("task-2", "edit {{foundFiles}}", TaskType.MODIFY,
            List.of("task-1"), Map.of("foundFiles", "task-1"), null));

        List<String> order = new ArrayList<>();
        for (var level : PlanParser.computeLevels(plan.getTasks())) {
            order.addAll(level);
        }
        plan.setExecutionOrder(order);

        List<String> task2Prompts = Collections.synchronizedList(new ArrayList<>());

        LLMProvider provider = new LLMProvider() {
            int callCount = 0;
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> tools) {
                callCount++;
                if (callCount == 1) {
                    // task-1: find files, return result
                    return Message.assistant("found: Foo.java, Bar.java");
                }
                // task-2: edit resolved prompt
                String userPrompt = messages.stream()
                    .filter(m -> m.role() == Role.USER)
                    .map(Message::content)
                    .findFirst().orElse("");
                task2Prompts.add(userPrompt);
                return Message.assistant("edited successfully");
            }
        };

        executor = new PlanExecutor(provider, registry, "/tmp");
        ExecutionPlan result = executor.execute(plan);

        assertThat(result.getStatus()).isEqualTo(PlanStatus.COMPLETED);
        // Task-2 should see resolved slot value, not the placeholder
        String task2Prompt = task2Prompts.get(0);
        assertThat(task2Prompt).contains("Foo.java");
        assertThat(task2Prompt).doesNotContain("{{foundFiles}}");
    }

    // === 3. EXPLORE task only gets read tools ===

    @Test
    void shouldFilterToolsForExploreTask() {
        // Verify registry setup
        assertThat(registry.count()).isEqualTo(7);

        ExecutionPlan plan = new ExecutionPlan("test explore filter");
        plan.addTask(new Task("task-1", "explore task", TaskType.EXPLORE, List.of(), Map.of(), null));
        plan.setExecutionOrder(List.of("task-1"));

        List<String> availableToolNames = new ArrayList<>();

        LLMProvider provider = new LLMProvider() {
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> tools) {
                // Record all tool names seen by the sub-agent
                availableToolNames.addAll(tools.stream().map(ToolDefinition::name).sorted().toList());
                return Message.assistant("done");
            }
        };

        executor = new PlanExecutor(provider, registry, "/tmp");
        executor.execute(plan);

        // EXPLORE tasks should only see read tools
        assertThat(availableToolNames).contains("glob", "grep", "read", "web_fetch");
        assertThat(availableToolNames).doesNotContain("write", "edit", "bash");
    }

    // === 4. VERIFY task only gets bash ===

    @Test
    void shouldFilterToolsForVerifyTask() {
        ExecutionPlan plan = new ExecutionPlan("test verify filter");
        plan.addTask(new Task("task-1", "verify task", TaskType.VERIFY, List.of(), Map.of(), null));
        plan.setExecutionOrder(List.of("task-1"));

        List<String> availableToolNames = new ArrayList<>();

        LLMProvider provider = new LLMProvider() {
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> tools) {
                availableToolNames.addAll(tools.stream().map(ToolDefinition::name).toList());
                return Message.assistant("build success");
            }
        };

        executor = new PlanExecutor(provider, registry, "/tmp");
        executor.execute(plan);

        assertThat(availableToolNames.get(0)).contains("bash");
        assertThat(availableToolNames.get(0)).doesNotContain("write", "edit", "glob", "grep");
    }

    // === 5. Task failure → RETRY → success ===

    @Test
    void shouldRetryOnFailure() {
        ExecutionPlan plan = new ExecutionPlan("test retry");
        plan.addTask(new Task("task-1", "flaky task", TaskType.MODIFY, List.of(), Map.of(), null));
        plan.setExecutionOrder(List.of("task-1"));

        LLMProvider provider = new LLMProvider() {
            int callCount = 0;
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> tools) {
                callCount++;
                // Check if this is a failure analysis call
                boolean isAnalysis = messages.stream()
                    .anyMatch(m -> m.content() != null && m.content().contains("Diagnose this task failure"));
                if (isAnalysis) {
                    return Message.assistant("RETRY");
                }
                if (callCount == 1) {
                    // First attempt: fail (throw exception)
                    throw new RuntimeException("simulated failure");
                }
                // Second attempt: succeed
                return Message.assistant("success on retry");
            }
        };

        executor = new PlanExecutor(provider, registry, "/tmp");
        ExecutionPlan result = executor.execute(plan);

        assertThat(result.getTask("task-1").getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(result.getTask("task-1").getResult()).isEqualTo("success on retry");
    }

    // === 6. Max retries exhausted → FAILED ===

    @Test
    void shouldFailAfterMaxRetries() {
        ExecutionPlan plan = new ExecutionPlan("test max retries");
        plan.addTask(new Task("task-1", "always failing", TaskType.MODIFY, List.of(), Map.of(), null));
        plan.setExecutionOrder(List.of("task-1"));

        LLMProvider provider = new LLMProvider() {
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> tools) {
                boolean isAnalysis = messages.stream()
                    .anyMatch(m -> m.content() != null && m.content().contains("Diagnose this task failure"));
                if (isAnalysis) {
                    return Message.assistant("RETRY");
                }
                throw new RuntimeException("always fails");
            }
        };

        executor = new PlanExecutor(provider, registry, "/tmp");
        ExecutionPlan result = executor.execute(plan);

        assertThat(result.getStatus()).isEqualTo(PlanStatus.FAILED);
        assertThat(result.getTask("task-1").getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.getTask("task-1").getErrorMessage()).contains("MAX_RETRIES");
    }

    // === 7. SKIP on failure ===

    @Test
    void shouldSkipOnRequest() {
        ExecutionPlan plan = new ExecutionPlan("test skip");
        plan.addTask(new Task("task-1", "skippable", TaskType.MODIFY, List.of(), Map.of(), null));
        plan.addTask(new Task("task-2", "downstream", TaskType.MODIFY, List.of("task-1"), Map.of(), null));
        List<String> order = List.of("task-1", "task-2");
        plan.setExecutionOrder(order);

        LLMProvider provider = new LLMProvider() {
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> tools) {
                boolean isAnalysis = messages.stream()
                    .anyMatch(m -> m.content() != null && m.content().contains("Diagnose this task failure"));
                if (isAnalysis) {
                    return Message.assistant("SKIP");
                }
                throw new RuntimeException("skip me");
            }
        };

        executor = new PlanExecutor(provider, registry, "/tmp");
        ExecutionPlan result = executor.execute(plan);

        assertThat(result.getTask("task-1").getStatus()).isEqualTo(TaskStatus.SKIPPED);
        // Downstream task should also be skipped
        assertThat(result.getTask("task-2").getStatus()).isEqualTo(TaskStatus.SKIPPED);
    }
}
