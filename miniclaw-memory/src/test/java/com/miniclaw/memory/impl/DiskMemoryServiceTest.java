package com.miniclaw.memory.impl;

import com.miniclaw.memory.MemoryEntry;
import com.miniclaw.memory.MemoryType;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class DiskMemoryServiceTest {

    @TempDir
    Path tempDir;

    private FileMemoryStore store;
    private DiskMemoryService service;

    @BeforeEach
    void setUp() {
        store = new FileMemoryStore(tempDir);
        service = new DiskMemoryService(store);
    }

    private MemoryEntry sampleEntry(String name) {
        return new MemoryEntry(name, "Description for " + name,
            MemoryType.USER, Instant.EPOCH, "Body content of " + name);
    }

    @Test
    void shouldSaveAndLoad() {
        service.save(sampleEntry("test-memory"));
        MemoryEntry loaded = service.load("user_test-memory.md");
        assertThat(loaded).isNotNull();
        assertThat(loaded.name()).isEqualTo("test-memory");
        assertThat(loaded.type()).isEqualTo(MemoryType.USER);
        assertThat(loaded.content()).contains("Body content");
    }

    @Test
    void shouldReturnIndexAsString() {
        service.save(sampleEntry("alpha"));
        String index = service.loadIndex();
        assertThat(index).contains("[alpha](user_alpha.md)");
    }

    @Test
    void shouldDeleteEntry() {
        service.save(sampleEntry("to-delete"));
        service.delete("user_to-delete.md");
        assertThat(store.exists("user_to-delete.md")).isFalse();
        assertThat(service.loadIndex()).doesNotContain("to-delete");
    }

    @Test
    void shouldListIndexEntries() {
        service.save(sampleEntry("first"));
        service.save(new MemoryEntry("second", "desc2", MemoryType.FEEDBACK,
            Instant.EPOCH, "body"));
        var entries = service.listIndex();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).filename()).isEqualTo("user_first.md");
    }

    @Test
    void shouldReturnEmptyIndexForEmptyDir() {
        assertThat(service.loadIndex()).isEmpty();
        assertThat(service.listIndex()).isEmpty();
    }

    @Test
    void shouldRegenerateIndex() {
        service.save(sampleEntry("a"));
        // Corrupt MEMORY.md
        store.write("MEMORY.md", "garbage");
        service.regenerateIndex();
        String index = service.loadIndex();
        assertThat(index).contains("[a](user_a.md)");
    }

    @Test
    void shouldOverwriteDuplicate() {
        service.save(sampleEntry("dup"));
        service.save(new MemoryEntry("dup", "updated desc",
            MemoryType.USER, Instant.EPOCH, "new body"));
        var entries = service.listIndex();
        // Should only have one entry, not two
        long count = entries.stream()
            .filter(e -> e.filename().equals("user_dup.md")).count();
        assertThat(count).isEqualTo(1);
        assertThat(entries.get(0).description()).isEqualTo("updated desc");
    }

    @Test
    void shouldReturnNullForMissingFile() {
        assertThat(service.load("nonexistent.md")).isNull();
    }
}
