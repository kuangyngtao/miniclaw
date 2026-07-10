package com.clawkit.engine.impl;

import com.clawkit.engine.SessionMeta;
import com.clawkit.engine.SessionService;
import com.clawkit.tools.schema.Message;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SessionServiceTest {

    @TempDir
    Path tempDir;

    private SessionService service;

    @BeforeEach
    void setUp() {
        // null provider → uses fallback summary
        service = new SessionService(tempDir, null);
    }

    @Test
    void shouldSaveWithFallbackSummaryWhenNoProvider() {
        List<Message> messages = List.of(
            Message.system("system prompt"),
            Message.user("refactor the UserService module to use dependency injection")
        );

        SessionMeta meta = service.save("refactor", messages);
        assertThat(meta.name()).isEqualTo("refactor");
        assertThat(meta.summary()).contains("refactor");
        assertThat(meta.messageCount()).isEqualTo(2);
    }

    @Test
    void shouldLoadSavedSession() {
        List<Message> messages = List.of(Message.user("test message"));
        SessionMeta meta = service.save("test", messages);

        List<Message> loaded = service.load(meta.id());
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).content()).isEqualTo("test message");
    }

    @Test
    void shouldListSavedSessions() {
        service.save("first", List.of(Message.user("hello")));
        service.save("second", List.of(Message.user("world")));

        List<SessionMeta> sessions = service.list();
        assertThat(sessions).hasSize(2);
    }

    @Test
    void shouldDeleteSession() {
        SessionMeta meta = service.save("delete-me", List.of(Message.user("bye")));
        assertThat(service.list()).hasSize(1);

        service.delete(meta.id());
        assertThat(service.list()).isEmpty();
    }

    @Test
    void shouldSearchSessionsByName() {
        service.save("重构用户模块", List.of(Message.user("refactor user service")));
        service.save("修复登录Bug", List.of(Message.user("fix login bug")));
        service.save("添加缓存层", List.of(Message.user("add caching layer")));

        List<SessionMeta> results = service.search("重构");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("重构用户模块");
    }

    @Test
    void shouldSearchSessionsBySummary() {
        service.save("test-session", List.of(Message.user("some task")));

        List<SessionMeta> results = service.search("some task");
        assertThat(results).hasSize(1);
    }

    @Test
    void shouldReturnEmptySearchResultsForNoMatch() {
        service.save("test", List.of(Message.user("hello")));

        // BM25: use query with no character overlap to avoid 2-gram false positives
        assertThat(service.search("zzz")).isEmpty();
    }

    @Test
    void shouldNotThrowOnDeleteMissingSession() {
        // SessionService delegates to store, which throws on missing
        assertThatCode(() -> service.delete("nonexistent"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReturnStatsWithBuckets() {
        service.save("recent", List.of(Message.user("hello")));
        service.save("also-recent", List.of(Message.user("world")));

        List<SessionService.AgeBucket> buckets = service.stats();
        // Both sessions are just saved, should be in < 7 days bucket
        assertThat(buckets).hasSize(1);
        assertThat(buckets.get(0).label()).isEqualTo("< 7 days");
        assertThat(buckets.get(0).count()).isEqualTo(2);
        assertThat(buckets.get(0).bytes()).isGreaterThan(0);
    }

    @Test
    void shouldPruneNoSessionsWhenAllAreRecent() {
        service.save("recent", List.of(Message.user("hello")));
        int pruned = service.prune(30);
        assertThat(pruned).isEqualTo(0);
        assertThat(service.list()).hasSize(1);
    }

    @Test
    void shouldPruneAllSessionsWithLargeCutoff() {
        service.save("a", List.of(Message.user("hello")));
        service.save("b", List.of(Message.user("world")));
        // All sessions are just saved, so 30 days won't prune any
        int pruned = service.prune(30);
        assertThat(pruned).isEqualTo(0);
        assertThat(service.list()).hasSize(2);
    }
}
