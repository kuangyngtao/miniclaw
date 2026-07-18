package com.clawkit.engine.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawkit.engine.ExecutionMode;
import com.clawkit.engine.MemoryHooks;
import com.clawkit.engine.ProviderGateway;
import com.clawkit.engine.RunPhase;
import com.clawkit.engine.RunScope;
import com.clawkit.memory.MemoryEntry;
import com.clawkit.memory.MemoryType;
import com.clawkit.memory.impl.DiskMemoryService;
import com.clawkit.provider.ModelRequest;
import com.clawkit.provider.ModelResponse;
import com.clawkit.provider.StreamObserver;
import com.clawkit.provider.TokenUsage;
import com.clawkit.tools.schema.Message;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class DefaultMemoryHooksTest {

    @TempDir Path tempDir;

    @Test
    void recallsRelevantMemoryWithoutMutatingHistory() {
        var store = new DiskMemoryService(tempDir);
        store.save(new MemoryEntry("database-policy", "数据库集成测试要求", MemoryType.FEEDBACK,
            Instant.now(), "集成测试必须连接真实数据库，禁止 mock。"));
        var hooks = new DefaultMemoryHooks(store, gateway("[]"), 1000, "cl100k_base");

        var messages = hooks.beforeRun(new MemoryHooks.MemoryRecallRequest(
            "数据库测试怎么做", 3, Set.of()));

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().content()).contains("禁止 mock");
    }

    @Test
    void forceExtractionPersistsAndReportsDuplicate() {
        var store = new DiskMemoryService(tempDir);
        String json = """
            [{"name":"user-role","description":"用户角色","type":"user","content":"用户是数据科学家。"}]
            """;
        var hooks = new DefaultMemoryHooks(store, gateway(json), 1000, "cl100k_base");
        var request = new MemoryHooks.MemoryExtractionRequest(
            List.of(Message.user("我是数据科学家")), 1,
            new RunScope("run-1", null, 1, RunPhase.REACT, ExecutionMode.REACT), true, 5);

        assertThat(hooks.afterRun(request).saved()).isEqualTo(1);
        assertThat(store.load("user_user-role.md")).isNotNull();
        assertThat(hooks.afterRun(request).skipped()).isEqualTo(1);
    }

    @Test
    void providerFailureDoesNotPersistAnything() {
        var store = new DiskMemoryService(tempDir);
        ProviderGateway failing = new ProviderGateway() {
            @Override public ModelResponse generate(ModelRequest request, RunScope scope) {
                throw new IllegalStateException("provider unavailable");
            }
            @Override public ModelResponse generateStream(ModelRequest request, RunScope scope,
                                                           StreamObserver observer) {
                throw new UnsupportedOperationException();
            }
        };
        var hooks = new DefaultMemoryHooks(store, failing, 1000, "cl100k_base");

        var result = hooks.afterRun(new MemoryHooks.MemoryExtractionRequest(
            List.of(Message.user("remember this")), 1, null, true, 5));

        assertThat(result).isEqualTo(MemoryHooks.MemorySaveResult.EMPTY);
        assertThat(store.listIndex()).isEmpty();
    }

    @Test
    void shorterNewSessionStartsFreshExtractionWindow() {
        var store = new DiskMemoryService(tempDir);
        String json = """
            [{"name":"user-role","description":"用户角色","type":"user","content":"用户是数据科学家。"}]
            """;
        var hooks = new DefaultMemoryHooks(store, gateway(json), 1, "cl100k_base");
        var longSession = java.util.stream.IntStream.range(0, 20)
            .mapToObj(i -> Message.user("old session message " + i)).toList();
        hooks.afterRun(new MemoryHooks.MemoryExtractionRequest(longSession, 1, null, true, 5));
        var newSession = java.util.stream.IntStream.range(0, 10)
            .mapToObj(i -> Message.user("new session message " + i)).toList();

        var result = hooks.afterRun(new MemoryHooks.MemoryExtractionRequest(
            newSession, 5, null, false, 3));

        assertThat(result.skipped()).isEqualTo(1);
    }

    private static ProviderGateway gateway(String response) {
        return new ProviderGateway() {
            @Override public ModelResponse generate(ModelRequest request, RunScope scope) {
                return ModelResponse.text(response, TokenUsage.EMPTY);
            }
            @Override public ModelResponse generateStream(ModelRequest request, RunScope scope,
                                                           StreamObserver observer) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
