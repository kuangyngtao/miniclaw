package com.clawkit.engine.impl;

import com.clawkit.provider.ModelRequest;
import com.clawkit.tools.schema.Role;
import com.clawkit.tools.schema.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** SHA-256 identity of the cacheable system/tool prefix; never records prompt text. */
public final class PromptFingerprint {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PromptFingerprint() {}

    public static String compute(ModelRequest request) {
        StringBuilder canonical = new StringBuilder();
        request.messages().stream()
            .takeWhile(message -> message.role() == Role.SYSTEM)
            .forEach(message -> appendPart(canonical, "system", message.content()));
        request.tools().stream()
            .sorted(Comparator.comparing(ToolDefinition::name))
            .forEach(tool -> {
                appendPart(canonical, "tool-name", tool.name());
                appendPart(canonical, "tool-description", tool.description());
                appendPart(canonical, "tool-schema", canonicalSchema(tool.inputSchema()));
            });
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void appendPart(StringBuilder target, String label, String value) {
        String safe = value != null ? value : "";
        target.append(label).append(':').append(safe.length()).append(':').append(safe).append('\n');
    }

    private static String canonicalSchema(Object schema) {
        if (schema == null) return "null";
        try {
            JsonNode node = schema instanceof CharSequence text
                ? MAPPER.readTree(text.toString()) : MAPPER.valueToTree(schema);
            return MAPPER.writeValueAsString(canonicalize(node));
        } catch (Exception ignored) {
            return String.valueOf(schema);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node == null) return MAPPER.nullNode();
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> sorted.set(name, canonicalize(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            node.forEach(item -> array.add(canonicalize(item)));
            return array;
        }
        return node;
    }
}
