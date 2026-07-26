package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strips connection details, URIs, tokens, and credentials from
 * evidence data before it enters the model context.
 *
 * <p>Only structural transformations — never alters health status,
 * HTTP status codes, container state, ports, resource metrics,
 * or database facts.
 */
public final class EvidenceSanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_STRING_LENGTH = 512;
    private static final int MAX_LOG_LINES = 100;
    private static final int MAX_NESTED_DEPTH = 10;

    private static final Set<String> STRIP_FIELDS = Set.of(
        "URL", "url", "URI", "uri", "DSN", "dsn", "connectionString",
        "jdbcUrl", "password", "token", "secret", "key", "apiKey",
        "credential", "credentials", "auth", "bearer",
        "publishers", "PublishedPort", "TargetPort"
    );

    // Reserved for future whitelist-based sanitization

    private EvidenceSanitizer() {}

    public static JsonNode sanitize(JsonNode input) {
        return sanitize(input, 0);
    }

    private static JsonNode sanitize(JsonNode node, int depth) {
        if (node == null || node.isNull()) return MAPPER.nullNode();
        if (depth > MAX_NESTED_DEPTH) return MAPPER.getNodeFactory().textNode("[truncated:depth]");

        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                String key = fields.next();
                JsonNode value = node.get(key);

                if (STRIP_FIELDS.contains(key)) {
                    out.put(key, "[redacted]");
                } else if ("logs".equals(key) || "Log".equals(key) || "output".equals(key)) {
                    out.set(key, truncateLog(value, depth));
                } else {
                    out.set(key, sanitize(value, depth + 1));
                }
            }
            return out;
        }

        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            int count = 0;
            for (JsonNode item : node) {
                if (count >= MAX_LOG_LINES && isLogContext(node)) {
                    out.add("[truncated:" + (node.size() - count) + " lines]");
                    break;
                }
                out.add(sanitize(item, depth + 1));
                count++;
            }
            return out;
        }

        if (node.isTextual()) {
            String text = node.asText();
            if (text.length() > MAX_STRING_LENGTH) {
                return MAPPER.getNodeFactory().textNode(text.substring(0, MAX_STRING_LENGTH) + "[truncated]");
            }
            // Strip obvious URIs and connection strings
            if (text.startsWith("http://") || text.startsWith("https://")
                || text.startsWith("jdbc:") || text.startsWith("ssh://")) {
                return MAPPER.getNodeFactory().textNode("[redacted]");
            }
            return node;
        }

        return node; // numbers, booleans pass through
    }

    private static JsonNode truncateLog(JsonNode node, int depth) {
        if (node.isTextual()) {
            String text = node.asText();
            String[] lines = text.split("\n");
            if (lines.length > MAX_LOG_LINES) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < MAX_LOG_LINES; i++) {
                    sb.append(truncateLine(lines[i])).append("\n");
                }
                sb.append("[truncated:").append(lines.length - MAX_LOG_LINES).append(" lines]");
                return MAPPER.getNodeFactory().textNode(sb.toString());
            }
            return MAPPER.getNodeFactory().textNode(truncateLine(text));
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            int count = 0;
            for (JsonNode item : node) {
                if (count >= MAX_LOG_LINES) {
                    out.add("[truncated:" + (node.size() - count) + " lines]");
                    break;
                }
                out.add(truncateLog(item, depth + 1));
                count++;
            }
            return out;
        }
        return sanitize(node, depth + 1);
    }

    private static String truncateLine(String line) {
        if (line.length() > MAX_STRING_LENGTH) {
            return line.substring(0, MAX_STRING_LENGTH) + "[truncated]";
        }
        return line;
    }

    private static boolean isLogContext(JsonNode node) {
        // Heuristic: arrays in log-related fields get line limit
        return node.size() > MAX_LOG_LINES;
    }
}
