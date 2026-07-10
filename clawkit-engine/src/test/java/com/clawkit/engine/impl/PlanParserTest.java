package com.clawkit.engine.impl;

import com.clawkit.tools.Result;
import com.clawkit.tools.schema.ExecutionPlan;
import com.clawkit.tools.schema.Task;
import com.clawkit.tools.schema.TaskStatus;
import com.clawkit.tools.schema.TaskType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlanParserTest {

    private final PlanParser parser = new PlanParser();

    @Test
    void validLinear3TaskPlan() {
        String json = """
            {
              "goal": "Rename getCwd to getCurrentWorkingDirectory",
              "tasks": {
                "task-1": {
                  "id": "task-1",
                  "description": "Search for all usages of getCwd",
                  "type": "EXPLORE",
                  "dependencies": []
                },
                "task-2": {
                  "id": "task-2",
                  "description": "Rename method in all found files",
                  "type": "MODIFY",
                  "dependencies": ["task-1"],
                  "inputSlots": {"fileList": "task-1"}
                },
                "task-3": {
                  "id": "task-3",
                  "description": "Compile and run tests",
                  "type": "VERIFY",
                  "dependencies": ["task-2"]
                }
              }
            }""";

        Result<ExecutionPlan> result = parser.parse(json);
        assertThat(result).isInstanceOf(Result.Ok.class);

        ExecutionPlan plan = ((Result.Ok<ExecutionPlan>) result).data();
        assertThat(plan.getGoal()).isEqualTo("Rename getCwd to getCurrentWorkingDirectory");
        assertThat(plan.taskCount()).isEqualTo(3);

        // Verify task types
        assertThat(plan.getTask("task-1").getTaskType()).isEqualTo(TaskType.EXPLORE);
        assertThat(plan.getTask("task-2").getTaskType()).isEqualTo(TaskType.MODIFY);
        assertThat(plan.getTask("task-3").getTaskType()).isEqualTo(TaskType.VERIFY);

        // Verify execution order respects dependencies
        assertThat(plan.getExecutionOrder()).isNotNull();
        assertThat(plan.getExecutionOrder().indexOf("task-1"))
            .isLessThan(plan.getExecutionOrder().indexOf("task-2"));
        assertThat(plan.getExecutionOrder().indexOf("task-2"))
            .isLessThan(plan.getExecutionOrder().indexOf("task-3"));

        // Verify inputSlots parsed
        assertThat(plan.getTask("task-2").getInputSlots())
            .containsEntry("fileList", "task-1");

        // Verify all tasks start as PENDING
        for (Task t : plan.getTasks().values()) {
            assertThat(t.getStatus()).isEqualTo(TaskStatus.PENDING);
        }
    }

    @Test
    void validParallelPlan() {
        String json = """
            {
              "goal": "Parallel exploration",
              "tasks": {
                "task-1": {
                  "id": "task-1",
                  "description": "Search Java files",
                  "type": "EXPLORE",
                  "dependencies": []
                },
                "task-2": {
                  "id": "task-2",
                  "description": "Search XML files",
                  "type": "EXPLORE",
                  "dependencies": []
                },
                "task-3": {
                  "id": "task-3",
                  "description": "Merge results",
                  "type": "MODIFY",
                  "dependencies": ["task-1", "task-2"]
                }
              }
            }""";

        Result<ExecutionPlan> result = parser.parse(json);
        assertThat(result).isInstanceOf(Result.Ok.class);

        ExecutionPlan plan = ((Result.Ok<ExecutionPlan>) result).data();
        assertThat(plan.taskCount()).isEqualTo(3);

        // task-1 and task-2 should execute before task-3
        int idx1 = plan.getExecutionOrder().indexOf("task-1");
        int idx2 = plan.getExecutionOrder().indexOf("task-2");
        int idx3 = plan.getExecutionOrder().indexOf("task-3");
        assertThat(idx1).isLessThan(idx3);
        assertThat(idx2).isLessThan(idx3);
    }

    @Test
    void planWithSlots() {
        String json = """
            {
              "goal": "Slot passing test",
              "tasks": {
                "task-1": {
                  "id": "task-1",
                  "description": "Find files",
                  "type": "EXPLORE",
                  "dependencies": [],
                  "outputSlot": "foundFiles"
                },
                "task-2": {
                  "id": "task-2",
                  "description": "Edit {{foundFiles}}",
                  "type": "MODIFY",
                  "dependencies": ["task-1"],
                  "inputSlots": {"foundFiles": "task-1"}
                }
              }
            }""";

        Result<ExecutionPlan> result = parser.parse(json);
        assertThat(result).isInstanceOf(Result.Ok.class);

        ExecutionPlan plan = ((Result.Ok<ExecutionPlan>) result).data();
        assertThat(plan.getTask("task-1").getOutputSlot()).isEqualTo("foundFiles");
        assertThat(plan.getTask("task-2").getDescription()).isEqualTo("Edit {{foundFiles}}");
        assertThat(plan.getTask("task-2").getInputSlots()).containsEntry("foundFiles", "task-1");
    }

    @Test
    void markdownFenceStripping() {
        String json = """
            ```json
            {
              "goal": "Test markdown fence",
              "tasks": {
                "task-1": {
                  "id": "task-1",
                  "description": "Test",
                  "type": "EXPLORE",
                  "dependencies": []
                }
              }
            }
            ```""";

        Result<ExecutionPlan> result = parser.parse(json);
        assertThat(result).isInstanceOf(Result.Ok.class);
    }

    @Test
    void missingGoal() {
        Result<ExecutionPlan> result = parser.parse("""
            {
              "tasks": {}
            }""");
        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<?>) result).error().errorCode()).isEqualTo("P-001");
    }

    @Test
    void missingTasks() {
        Result<ExecutionPlan> result = parser.parse("""
            {
              "goal": "Empty tasks"
            }""");
        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void emptyTasks() {
        Result<ExecutionPlan> result = parser.parse("""
            {
              "goal": "Empty tasks",
              "tasks": {}
            }""");
        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void duplicateTaskIds() {
        Result<ExecutionPlan> result = parser.parse("""
            {
              "goal": "Duplicate task IDs",
              "tasks": {
                "task-1": {
                  "id": "task-1",
                  "description": "First",
                  "type": "EXPLORE",
                  "dependencies": []
                },
                "task-2": {
                  "id": "task-1",
                  "description": "Second",
                  "type": "MODIFY",
                  "dependencies": []
                }
              }
            }""");
        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<?>) result).error().errorCode()).isEqualTo("P-002");
    }

    @Test
    void missingDescription() {
        Result<ExecutionPlan> result = parser.parse("""
            {
              "goal": "Missing description",
              "tasks": {
                "task-1": {
                  "id": "task-1",
                  "type": "EXPLORE",
                  "dependencies": []
                }
              }
            }""");
        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<?>) result).error().errorCode()).isEqualTo("P-002");
    }

    @Test
    void circularDependency() {
        String json = """
            {
              "goal": "Circular dependency",
              "tasks": {
                "task-1": {
                  "id": "task-1",
                  "description": "First",
                  "type": "MODIFY",
                  "dependencies": ["task-2"]
                },
                "task-2": {
                  "id": "task-2",
                  "description": "Second",
                  "type": "MODIFY",
                  "dependencies": ["task-1"]
                }
              }
            }""";

        Result<ExecutionPlan> result = parser.parse(json);
        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<?>) result).error().errorCode()).isEqualTo("P-003");
    }

    @Test
    void missingDependencyTarget() {
        Result<ExecutionPlan> result = parser.parse("""
            {
              "goal": "Missing dependency",
              "tasks": {
                "task-1": {
                  "id": "task-1",
                  "description": "Depends on nonexistent",
                  "type": "MODIFY",
                  "dependencies": ["task-nonexistent"]
                }
              }
            }""");
        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<?>) result).error().errorCode()).isEqualTo("P-003");
    }

    @Test
    void unknownTypeDefaultsToModify() {
        Result<ExecutionPlan> result = parser.parse("""
            {
              "goal": "Unknown type",
              "tasks": {
                "task-1": {
                  "id": "task-1",
                  "description": "Test",
                  "type": "FOOBAR",
                  "dependencies": []
                }
              }
            }""");
        assertThat(result).isInstanceOf(Result.Ok.class);
        ExecutionPlan plan = ((Result.Ok<ExecutionPlan>) result).data();
        assertThat(plan.getTask("task-1").getTaskType()).isEqualTo(TaskType.MODIFY);
    }

    @Test
    void invalidJson() {
        Result<ExecutionPlan> result = parser.parse("not json at all");
        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<?>) result).error().errorCode()).isEqualTo("P-001");
    }

    @Test
    void computeLevelsLinear() {
        ExecutionPlan plan = new ExecutionPlan("test");
        Task t1 = new Task("task-1", "first", TaskType.EXPLORE, List.of(), Map.of(), null);
        Task t2 = new Task("task-2", "second", TaskType.MODIFY, List.of("task-1"), Map.of(), null);
        Task t3 = new Task("task-3", "third", TaskType.VERIFY, List.of("task-2"), Map.of(), null);
        plan.addTask(t1);
        plan.addTask(t2);
        plan.addTask(t3);

        var levels = PlanParser.computeLevels(plan.getTasks());
        assertThat(levels).hasSize(3);
        assertThat(levels.get(0)).containsExactly("task-1");
        assertThat(levels.get(1)).containsExactly("task-2");
        assertThat(levels.get(2)).containsExactly("task-3");
    }

    @Test
    void computeLevelsParallel() {
        ExecutionPlan plan = new ExecutionPlan("test");
        Task t1 = new Task("task-1", "first", TaskType.EXPLORE, List.of(), Map.of(), null);
        Task t2 = new Task("task-2", "second", TaskType.EXPLORE, List.of(), Map.of(), null);
        Task t3 = new Task("task-3", "third", TaskType.MODIFY, List.of("task-1", "task-2"), Map.of(), null);
        plan.addTask(t1);
        plan.addTask(t2);
        plan.addTask(t3);

        var levels = PlanParser.computeLevels(plan.getTasks());
        assertThat(levels).hasSize(2);
        assertThat(levels.get(0)).containsExactlyInAnyOrder("task-1", "task-2");
        assertThat(levels.get(1)).containsExactly("task-3");
    }
}
