package com.miniclaw.memory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single memory entry stored as a Markdown file with YAML frontmatter.
 */
public record MemoryEntry(
    String name,
    String description,
    MemoryType type,
    Instant timestamp,
    String content
) {
    public String filename() {
        return type.name().toLowerCase() + "_" + sanitizeName(name) + ".md";
    }

    public Map<String, String> toFrontmatter() {
        Map<String, String> fm = new LinkedHashMap<>();
        fm.put("name", name);
        fm.put("description", description);
        fm.put("type", type.name().toLowerCase());
        fm.put("timestamp", timestamp.toString());
        return fm;
    }

    private static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
