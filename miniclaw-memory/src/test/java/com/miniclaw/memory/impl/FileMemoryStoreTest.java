package com.miniclaw.memory.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileMemoryStoreTest {

    @TempDir
    Path tempDir;

    private FileMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new FileMemoryStore(tempDir);
    }

    @Test
    void shouldWriteAndRead() {
        store.write("test.md", "hello world");
        assertThat(store.read("test.md")).isEqualTo("hello world");
    }

    @Test
    void shouldAppendLines() {
        store.append("log.md", "line 1");
        store.append("log.md", "line 2");
        String content = store.read("log.md");
        assertThat(content).contains("line 1");
        assertThat(content).contains("line 2");
    }

    @Test
    void shouldListOnlyMdFiles() throws IOException {
        Files.writeString(tempDir.resolve("a.md"), "a");
        Files.writeString(tempDir.resolve("b.txt"), "b");
        Files.writeString(tempDir.resolve("c.md"), "c");
        List<String> files = store.listWorkFiles();
        assertThat(files).containsExactlyInAnyOrder("a.md", "c.md");
    }

    @Test
    void shouldReturnEmptyStringForMissingFile() {
        assertThat(store.read("nonexistent.md")).isEmpty();
    }

    @Test
    void shouldDeleteFile() {
        store.write("tmp.md", "data");
        assertThat(store.exists("tmp.md")).isTrue();
        store.delete("tmp.md");
        assertThat(store.exists("tmp.md")).isFalse();
    }

    @Test
    void shouldRejectPathTraversal() {
        assertThatThrownBy(() -> store.read("../../etc/passwd"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Path traversal");
    }

    @Test
    void shouldCreateDirectoryOnConstruction() throws IOException {
        Path nested = tempDir.resolve("new/sub");
        FileMemoryStore nestedStore = new FileMemoryStore(nested);
        assertThat(Files.isDirectory(nested)).isTrue();
    }

    @Test
    void shouldExposeBasePath() {
        assertThat(store.basePath()).isEqualTo(tempDir);
    }
}
