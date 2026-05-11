package com.miniclaw.memory.impl;

import com.miniclaw.memory.MemoryStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-based implementation of MemoryStore using java.nio.file.Files.
 * All paths are resolved relative to basePath with path traversal protection.
 */
public class FileMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(FileMemoryStore.class);
    private final Path basePath;

    public FileMemoryStore(Path basePath) {
        this.basePath = basePath.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create base directory: " + this.basePath, e);
        }
    }

    @Override
    public String read(String filePath) {
        Path resolved = resolve(filePath);
        try {
            return Files.readString(resolved);
        } catch (NoSuchFileException e) {
            return "";
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read: " + resolved, e);
        }
    }

    @Override
    public void write(String filePath, String content) {
        Path resolved = resolve(filePath);
        try {
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write: " + resolved, e);
        }
    }

    @Override
    public void append(String filePath, String line) {
        Path resolved = resolve(filePath);
        try {
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, line + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append: " + resolved, e);
        }
    }

    @Override
    public List<String> listWorkFiles() {
        try (Stream<Path> stream = Files.list(basePath)) {
            return stream
                .filter(p -> p.toString().endsWith(".md"))
                .map(p -> basePath.relativize(p).toString())
                .sorted()
                .toList();
        } catch (IOException e) {
            log.warn("Cannot list files in {}: {}", basePath, e.getMessage());
            return List.of();
        }
    }

    public void delete(String filePath) {
        Path resolved = resolve(filePath);
        try {
            Files.deleteIfExists(resolved);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete: " + resolved, e);
        }
    }

    public boolean exists(String filePath) {
        return Files.exists(resolve(filePath));
    }

    public Path basePath() {
        return basePath;
    }

    private Path resolve(String filePath) {
        Path p = basePath.resolve(filePath).normalize();
        if (!p.startsWith(basePath)) {
            throw new SecurityException("Path traversal detected: " + filePath);
        }
        return p;
    }
}
