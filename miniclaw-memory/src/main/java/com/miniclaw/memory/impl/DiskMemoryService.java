package com.miniclaw.memory.impl;

import com.miniclaw.memory.MemoryEntry;
import com.miniclaw.memory.MemoryType;
import com.miniclaw.memory.YamlFrontmatterParser;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-level disk memory service.
 * Manages typed memory entries with YAML frontmatter under a memory directory,
 * and maintains a MEMORY.md index file.
 */
public class DiskMemoryService {

    private static final Logger log = LoggerFactory.getLogger(DiskMemoryService.class);
    private static final String INDEX_FILE = "MEMORY.md";
    private static final Pattern INDEX_LINE = Pattern.compile(
        "^\\s*-\\s+\\[(.+?)\\]\\((.+?\\.md)\\)\\s*[-–—]\\s*(.+)$");

    private final FileMemoryStore store;

    public DiskMemoryService(Path memoryDir) {
        this.store = new FileMemoryStore(memoryDir.toAbsolutePath().normalize());
        if (!store.exists(INDEX_FILE)) {
            store.write(INDEX_FILE, "");
        }
    }

    /** For testing: inject a custom FileMemoryStore. */
    DiskMemoryService(FileMemoryStore store) {
        this.store = store;
        if (!store.exists(INDEX_FILE)) {
            store.write(INDEX_FILE, "");
        }
    }

    // ─── Index Loading ───────────────────────────────────────────────

    /**
     * Read MEMORY.md and return its content as a formatted string
     * suitable for system prompt injection.
     * Returns empty string if MEMORY.md is empty or absent.
     */
    public String loadIndex() {
        String content = store.read(INDEX_FILE);
        if (content == null || content.isBlank()) {
            return "";
        }
        return content.stripTrailing();
    }

    // ─── CRUD ────────────────────────────────────────────────────────

    public void save(MemoryEntry entry) {
        String fullContent = YamlFrontmatterParser.build(
            entry.toFrontmatter(), entry.content());
        store.write(entry.filename(), fullContent);

        removeFromIndex(entry.filename());
        String line = "- [" + entry.name() + "](" + entry.filename()
            + ") — " + entry.description();
        store.append(INDEX_FILE, line);
        log.info("Memory saved: {} ({})", entry.filename(), entry.description());
    }

    public MemoryEntry load(String filename) {
        String content = store.read(filename);
        if (content == null || content.isEmpty()) return null;

        Map<String, String> fm = YamlFrontmatterParser.parseFrontmatter(content);
        if (fm.isEmpty()) return null;

        String name = fm.getOrDefault("name", filename.replace(".md", ""));
        String description = fm.getOrDefault("description", "");
        MemoryType type = parseType(fm.getOrDefault("type", "reference"));
        Instant ts = parseTimestamp(fm.getOrDefault("timestamp", null));
        String body = YamlFrontmatterParser.stripFrontmatter(content);

        return new MemoryEntry(name, description, type, ts, body);
    }

    public void delete(String filename) {
        store.delete(filename);
        removeFromIndex(filename);
        log.info("Memory deleted: {}", filename);
    }

    // ─── Index Management ────────────────────────────────────────────

    public List<IndexEntry> listIndex() {
        String content = store.read(INDEX_FILE);
        if (content == null || content.isBlank()) return List.of();
        return parseIndex(content);
    }

    /**
     * Regenerate MEMORY.md by scanning all .md files and reading their frontmatter.
     */
    public void regenerateIndex() {
        List<String> files = store.listWorkFiles().stream()
            .filter(f -> !f.equals(INDEX_FILE))
            .toList();

        StringBuilder sb = new StringBuilder();
        for (String file : files) {
            MemoryEntry entry = load(file);
            if (entry != null) {
                sb.append("- [").append(entry.name()).append("](")
                  .append(file).append(") — ")
                  .append(entry.description()).append("\n");
            }
        }
        store.write(INDEX_FILE, sb.toString().stripTrailing());
    }

    public record IndexEntry(String name, String filename, String description) {}

    // ─── Private Helpers ─────────────────────────────────────────────

    private List<IndexEntry> parseIndex(String content) {
        List<IndexEntry> entries = new ArrayList<>();
        for (String line : content.split("\n")) {
            Matcher m = INDEX_LINE.matcher(line);
            if (m.matches()) {
                entries.add(new IndexEntry(m.group(1), m.group(2), m.group(3)));
            }
        }
        return entries;
    }

    private void removeFromIndex(String filename) {
        String content = store.read(INDEX_FILE);
        if (content == null || content.isBlank()) return;
        String escaped = filename.replace(".", "\\.");
        String updated = content.replaceAll(
            "(?m)^\\s*-\\s+\\[.+?\\]\\(" + escaped + "\\)\\s*[-–—].*$\n?", "");
        store.write(INDEX_FILE, updated.stripTrailing() + "\n");
    }

    private static MemoryType parseType(String s) {
        try {
            return MemoryType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MemoryType.REFERENCE;
        }
    }

    private static Instant parseTimestamp(String s) {
        if (s == null || s.isBlank()) return Instant.now();
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
