package com.miniclaw.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses and generates YAML frontmatter in Markdown files.
 * Format:
 *   ---
 *   key: value
 *   ---
 *   Body content...
 */
public final class YamlFrontmatterParser {

    private static final ObjectMapper YAML = new ObjectMapper(
        new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
    private static final String DELIM = "---\n";

    private YamlFrontmatterParser() {}

    /**
     * Parse YAML frontmatter from full file content.
     * Returns an empty map if no valid frontmatter is found.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> parseFrontmatter(String content) {
        if (content == null || !content.startsWith(DELIM)) {
            return Map.of();
        }
        int end = content.indexOf("\n" + DELIM, 4);
        if (end == -1) return Map.of();
        String yamlBlock = content.substring(4, end);
        try {
            Map<String, Object> raw = YAML.readValue(yamlBlock, Map.class);
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((k, v) -> result.put(k, v != null ? v.toString() : ""));
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Strip the YAML frontmatter and return only the Markdown body.
     */
    public static String stripFrontmatter(String content) {
        if (content == null || !content.startsWith(DELIM)) {
            return content;
        }
        int end = content.indexOf("\n" + DELIM, 4);
        if (end == -1) return content;
        return content.substring(end + 5).replaceFirst("^\n+", "");
    }

    /**
     * Build a full Markdown file content from frontmatter fields and a body.
     */
    public static String build(Map<String, String> frontmatter, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append(DELIM);
        try {
            sb.append(YAML.writeValueAsString(frontmatter));
        } catch (Exception e) {
            frontmatter.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
        }
        sb.append(DELIM);
        if (body != null) {
            sb.append(body);
        }
        return sb.toString();
    }
}
