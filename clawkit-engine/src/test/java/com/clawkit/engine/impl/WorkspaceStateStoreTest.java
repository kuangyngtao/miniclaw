package com.clawkit.engine.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceStateStoreTest {
    @TempDir Path root;

    @Test
    void roundTripsPlanTodoAndRecallContext() throws Exception {
        var store = new WorkspaceStateStore(root);
        store.writePlan("# Plan\n\nDo it");
        var args = new ObjectMapper().readTree("""
            {"todos":[{"content":"first","status":"completed"},
                       {"content":"second","activeForm":"doing second","status":"in_progress"}]}
            """);
        store.writeTodo(args);

        assertThat(store.read(".clawkit/plan.md")).contains("Do it");
        assertThat(Files.readString(root.resolve(".clawkit/todo.md")))
            .contains("- [x] first", "- [~] doing second");
        assertThat(store.recallContext()).singleElement()
            .satisfies(m -> assertThat(m.content()).contains("当前计划", "当前任务进度"));
    }

    @Test
    void missingStateProducesNoContextAndEscapesAreRejected() {
        var store = new WorkspaceStateStore(root);
        assertThat(store.recallContext()).isEmpty();
        assertThat(store.read("../outside.txt")).isNull();
    }
}
