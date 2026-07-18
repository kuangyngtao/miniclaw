package com.clawkit.engine.impl;

import com.clawkit.engine.SessionMeta;
import com.clawkit.engine.SessionError;
import com.clawkit.engine.SessionStoreException;
import com.clawkit.tools.schema.Message;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSessionStoreTest {

    @TempDir
    Path tempDir;

    private FileSessionStore store;

    @BeforeEach
    void setUp() {
        store = new FileSessionStore(tempDir);
    }

    @Test
    void shouldSaveAndLoadSession() {
        List<Message> messages = List.of(
            Message.system("You are a helpful assistant."),
            Message.user("Help me refactor UserService."),
            Message.assistant("I'll start by reading the file.")
        );

        SessionMeta meta = store.save("a1b2c3d4", "refactor", messages, "Refactored UserService.");

        assertThat(meta.id()).isEqualTo("a1b2c3d4");
        assertThat(meta.name()).isEqualTo("refactor");
        assertThat(meta.messageCount()).isEqualTo(3);

        List<Message> loaded = store.loadMessages("a1b2c3d4");
        assertThat(loaded).hasSize(3);
        assertThat(loaded.get(0).role()).isEqualTo(com.clawkit.tools.schema.Role.SYSTEM);
        assertThat(loaded.get(1).role()).isEqualTo(com.clawkit.tools.schema.Role.USER);
    }

    @Test
    void shouldListSessionsSortedByUpdatedAt() throws Exception {
        store.save("id1", "first", List.of(Message.user("hello")), "summary1");
        Thread.sleep(2); // ensure distinct timestamps
        store.save("id2", "second", List.of(Message.user("world")), "summary2");

        List<SessionMeta> sessions = store.listSessions();
        assertThat(sessions).hasSize(2);
        // newest first by updatedAt
        assertThat(sessions.get(0).id()).isEqualTo("id2");
    }

    @Test
    void shouldDeleteSession() {
        store.save("id1", "test", List.of(Message.user("hello")), "summary");

        store.delete("id1");
        assertThat(store.listSessions()).isEmpty();
        assertThatThrownBy(() -> store.loadMessages("id1"))
            .isInstanceOfSatisfying(SessionStoreException.class,
                e -> assertThat(e.error()).isEqualTo(SessionError.NOT_FOUND));
    }

    @Test
    void shouldReturnMetaWithoutLoadingFullSession() {
        store.save("id1", "test", List.of(Message.user("hello")), "summary");

        SessionMeta meta = store.getMeta("id1");
        assertThat(meta.name()).isEqualTo("test");
        assertThat(meta.summary()).isEqualTo("summary");
    }

    @Test
    void shouldReturnNullMetaForMissingSession() {
        assertThat(store.getMeta("nonexistent")).isNull();
    }

    @Test
    void shouldRejectLoadOfMissingSession() {
        assertThatThrownBy(() -> store.loadMessages("nonexistent"))
            .isInstanceOfSatisfying(SessionStoreException.class,
                e -> assertThat(e.error()).isEqualTo(SessionError.NOT_FOUND));
    }

    @Test
    void shouldReportCorruptedJson() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("broken.json"), "{not-json");

        assertThatThrownBy(() -> store.load("broken"))
            .isInstanceOfSatisfying(SessionStoreException.class, e -> {
                assertThat(e.error()).isEqualTo(SessionError.CORRUPTED_JSON);
                assertThat(e.sessionId()).isEqualTo("broken");
            });
    }

    @Test
    void shouldReportUnsupportedVersion() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("future.json"), """
            {"schemaVersion":999,"meta":null,"messages":[],"metadata":{}}
            """);

        assertThatThrownBy(() -> store.load("future"))
            .isInstanceOfSatisfying(SessionStoreException.class,
                e -> assertThat(e.error()).isEqualTo(SessionError.UNSUPPORTED_VERSION));
    }

    @Test
    void shouldGenerateShortSessionIds() {
        String id = FileSessionStore.generateSessionId();
        assertThat(id).hasSize(8);
        assertThat(id).matches("[0-9a-f]+");
    }

    @Test
    void shouldExtractFirstUserMessage() {
        List<Message> messages = List.of(
            Message.system("system"),
            Message.assistant("assistant"),
            Message.user("first user message"),
            Message.user("second user message")
        );

        SessionMeta meta = store.save("id1", "test", messages, "");
        assertThat(meta.firstUserMessage()).isEqualTo("first user message");
    }
}
