package com.clawkit.tools.remote;

import com.clawkit.tools.mcp.McpToolDef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Computes canonical tool-contract hashes for remote attestation.
 *
 * <h3>Two hash types</h3>
 * <ol>
 *   <li><b>Tool-set hash</b> — SHA-256 of sorted tool names (first 8 bytes hex).
 *       Compatible with the existing OPS {@code toolSetHash} in
 *       {@code OpsMcpServer.initialize()}.</li>
 *   <li><b>Tool-contract hash</b> — full SHA-256 hex of a canonical JSON array
 *       of [name, inputSchema, outputSchema, annotations] sorted by name.
 *       This detects schema widening, annotation drift, and extra/missing tools.
 *       Does NOT include description text to avoid cosmetic-only drift.</li>
 * </ol>
 *
 * <p>Design: REMOTE-0 §8.1.
 */
public final class ToolContractHash {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

    static {
        CANONICAL_MAPPER.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        CANONICAL_MAPPER.configure(SerializationFeature.INDENT_OUTPUT, false);
    }

    private ToolContractHash() {}

    // ── Tool-set hash (name-only, 64-bit truncated) ──────────────────

    /**
     * Compute the tool-set hash from tool names (SHA-256, first 8 bytes hex).
     * Compatible with existing OPS {@code toolSetHash}.
     */
    public static String computeToolSetHashFromNames(Set<String> names) {
        String[] sorted = names.toArray(String[]::new);
        java.util.Arrays.sort(sorted);
        MessageDigest md = sha256();
        for (String name : sorted) {
            md.update(name.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(md.digest(), 0, 8);
    }

    /**
     * Compute the tool-set hash from MCP tool definitions (name-only).
     */
    public static String computeToolSetHashFromTools(List<McpToolDef> tools) {
        Set<String> names = tools.stream().map(McpToolDef::name).collect(Collectors.toSet());
        return computeToolSetHashFromNames(names);
    }

    // ── Tool-contract hash (full SHA-256, canonical JSON) ─────────────

    /**
     * Compute the canonical tool-contract hash from MCP tool definitions.
     *
     * <p>Canonical form: SHA-256 of a JSON array of {@code [name, inputSchema,
     * outputSchema, annotations]} objects, sorted by tool name. Description is
     * intentionally excluded — cosmetic text changes should not break attestation.
     *
     * <p>To ensure deterministic output regardless of how the JsonNode trees
     * were created, each field is individually serialized through the
     * canonical mapper (which sorts keys) and then concatenated in a fixed
     * field order.
     *
     * @return full SHA-256 hex string (64 characters)
     */
    public static String computeFromMcpTools(List<McpToolDef> tools) {
        List<McpToolDef> sorted = tools.stream()
            .sorted(Comparator.comparing(McpToolDef::name))
            .toList();

        try {
            // Build canonical JSON via manual serialization of each field.
            // We serialize each tool as {"annotations":...,"inputSchema":...,"name":"...","outputSchema":...}
            // and each field's value through the canonical mapper for key-sorted output.
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) sb.append(",");
                McpToolDef tool = sorted.get(i);
                String nameJson = CANONICAL_MAPPER.writeValueAsString(tool.name());
                String isJson = jsonOrEmpty(CANONICAL_MAPPER, normalizeNode(tool.inputSchema()));
                String osJson = jsonOrEmpty(CANONICAL_MAPPER, normalizeNode(tool.outputSchema()));
                String anJson = jsonOrEmpty(CANONICAL_MAPPER, normalizeNode(tool.annotations()));
                // Fixed alphabetical field order: annotations, inputSchema, name, outputSchema
                sb.append("{\"annotations\":").append(anJson)
                  .append(",\"inputSchema\":").append(isJson)
                  .append(",\"name\":").append(nameJson)
                  .append(",\"outputSchema\":").append(osJson)
                  .append("}");
            }
            sb.append("]");
            String canonical = sb.toString();
            MessageDigest md = sha256();
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute tool-contract hash", e);
        }
    }

    private static String jsonOrEmpty(ObjectMapper mapper, JsonNode node) {
        if (node == null || node.isMissingNode() || (node.isObject() && node.isEmpty())) {
            return "{}";
        }
        return toCanonicalJson(node);
    }

    /**
     * Convert any JsonNode to a canonical JSON string with recursively
     * sorted keys. Unlike {@code mapper.writeValueAsString(JsonNode)},
     * this sorts ObjectNode keys recursively for deterministic output.
     */
    private static String toCanonicalJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return "null";
        if (node.isTextual()) return "\"" + escapeJson(node.asText()) + "\"";
        if (node.isBoolean()) return node.asBoolean() ? "true" : "false";
        if (node.isNumber()) {
            if (node.isIntegralNumber()) return String.valueOf(node.asLong());
            return String.valueOf(node.asDouble());
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (JsonNode child : node) {
                if (!first) sb.append(",");
                sb.append(toCanonicalJson(child));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        if (node.isObject()) {
            // Collect fields sorted by key
            TreeMap<String, String> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                sorted.put(entry.getKey(), toCanonicalJson(entry.getValue()));
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
                sb.append(entry.getValue());
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        return "null";
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 10);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static JsonNode normalizeNode(JsonNode node) {
        return node != null && !node.isMissingNode() ? node : CANONICAL_MAPPER.createObjectNode();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
