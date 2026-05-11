package com.miniclaw.tools.impl;

import com.miniclaw.tools.Result;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TodoWriteToolTest {

    private final TodoWriteTool tool = new TodoWriteTool();

    @Test
    void shouldCreateAndFormatTaskList() {
        String input = """
            {
              "todos": [
                {"content": "Fix login bug", "status": "in_progress", "activeForm": "Fixing login bug"},
                {"content": "Add unit tests", "status": "pending", "activeForm": "Adding unit tests"}
              ]
            }""";

        Result<String> result = tool.execute(input);

        assertThat(result).isInstanceOf(Result.Ok.class);
        String output = ((Result.Ok<String>) result).data();
        assertThat(output).contains("[1/2] Fixing login bug");
        assertThat(output).contains("[2/2] [pending] Add unit tests");
    }

    @Test
    void shouldRejectInvalidStatus() {
        String input = """
            {
              "todos": [
                {"content": "Task", "status": "doing", "activeForm": "Doing task"}
              ]
            }""";

        Result<String> result = tool.execute(input);

        assertThat(result).isInstanceOf(Result.Err.class);
        String error = ((Result.Err<String>) result).error().message();
        assertThat(error).contains("非法 status");
    }

    @Test
    void shouldRejectEmptyContent() {
        String input = """
            {
              "todos": [
                {"content": "", "status": "pending", "activeForm": "Something"}
              ]
            }""";

        Result<String> result = tool.execute(input);

        assertThat(result).isInstanceOf(Result.Err.class);
        String error = ((Result.Err<String>) result).error().message();
        assertThat(error).contains("缺少 'content'");
    }

    @Test
    void shouldPreserveStateAcrossMultipleCalls() {
        tool.execute("""
            {
              "todos": [
                {"content": "Task A", "status": "completed", "activeForm": "Doing task A"},
                {"content": "Task B", "status": "in_progress", "activeForm": "Doing task B"}
              ]
            }""");

        Result<String> result = tool.execute("""
            {
              "todos": [
                {"content": "Task A", "status": "completed", "activeForm": "Doing task A"},
                {"content": "Task B", "status": "completed", "activeForm": "Doing task B"},
                {"content": "Task C", "status": "pending", "activeForm": "Adding task C"}
              ]
            }""");

        assertThat(result).isInstanceOf(Result.Ok.class);
        String output = ((Result.Ok<String>) result).data();
        assertThat(output).contains("[1/3] [completed] Task A");
        assertThat(output).contains("[2/3] [completed] Task B");
        assertThat(output).contains("[3/3] [pending] Task C");
    }

    @Test
    void shouldDisplayCompletedTasksWithPrefix() {
        String input = """
            {
              "todos": [
                {"content": "Read file", "status": "completed", "activeForm": "Reading file"}
              ]
            }""";

        Result<String> result = tool.execute(input);

        String output = ((Result.Ok<String>) result).data();
        assertThat(output).contains("[completed]");
        assertThat(output).contains("Read file");
    }

    @Test
    void shouldHandleEmptyTodoList() {
        String input = "{\"todos\": []}";

        Result<String> result = tool.execute(input);

        String output = ((Result.Ok<String>) result).data();
        assertThat(output).contains("任务列表为空");
    }
}
