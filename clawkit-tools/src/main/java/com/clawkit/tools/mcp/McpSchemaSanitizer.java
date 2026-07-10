package com.clawkit.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JSON Schema 清洗器。确保 MCP server 返回的 Schema 可被 LLM 理解。 */
public final class McpSchemaSanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_DESC_LENGTH = 1000;

    private static final String[] STRIP_KEYS = {
        "$schema", "$id", "$ref", "$defs", "definitions"
    };

    private McpSchemaSanitizer() {}

    public static String sanitize(JsonNode raw) {
        ObjectNode node = raw.deepCopy();
        stripMeta(node);
        resolveAnyOf(node);
        resolveOneOf(node);
        ensureTypeAndProps(node);
        truncateDescription(node);
        if (!"object".equals(node.path("type").asText())) {
            node = wrapInObject(node);
        }
        return node.toString();
    }

    /** 删除 $schema / $id / $ref / $defs / definitions 等 LLM 看不懂的字段 */
    private static void stripMeta(ObjectNode node) {
        for (String key : STRIP_KEYS) {
            node.remove(key);
        }
        // 递归处理 properties 内的子 schema
        JsonNode props = node.get("properties");
        if (props != null && props.isObject()) {
            var fields = props.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (entry.getValue().isObject()) {
                    stripMeta((ObjectNode) entry.getValue());
                }
            }
        }
    }

    /** anyOf → description 文本追加: "Must satisfy one of: ..." */
    private static void resolveAnyOf(ObjectNode node) {
        JsonNode anyOf = node.remove("anyOf");
        if (anyOf == null || !anyOf.isArray() || anyOf.size() == 0) return;

        StringBuilder sb = new StringBuilder("[anyOf] Must satisfy one of:");
        int i = 0;
        for (JsonNode alt : anyOf) {
            if (i >= 5) { sb.append(" ...more alternatives omitted"); break; }
            sb.append("\n  Option ").append(i + 1).append(": ")
              .append(summarizeSchema(alt, 120));
            i++;
        }
        appendDescription(node, sb.toString());
    }

    /** oneOf → description 文本追加: "Exactly one of: ..." */
    private static void resolveOneOf(ObjectNode node) {
        JsonNode oneOf = node.remove("oneOf");
        if (oneOf == null || !oneOf.isArray() || oneOf.size() == 0) return;

        StringBuilder sb = new StringBuilder("[oneOf] Exactly one of:");
        int i = 0;
        for (JsonNode alt : oneOf) {
            if (i >= 5) { sb.append(" ...more alternatives omitted"); break; }
            sb.append("\n  Option ").append(i + 1).append(": ")
              .append(summarizeSchema(alt, 120));
            i++;
        }
        appendDescription(node, sb.toString());
    }

    /** 确保有 type 字段（默认 object）和 properties 字段（默认 {}） */
    private static void ensureTypeAndProps(ObjectNode node) {
        if (!node.has("type")) {
            node.put("type", "object");
        }
        if (!node.has("properties")) {
            node.putObject("properties");
        }
    }

    /** 截断超长 description */
    private static void truncateDescription(ObjectNode node) {
        JsonNode desc = node.get("description");
        if (desc != null && desc.isTextual() && desc.asText().length() > MAX_DESC_LENGTH) {
            node.put("description", desc.asText().substring(0, MAX_DESC_LENGTH) + "...");
        }
    }

    /** 非 object 类型用 {"type":"object","properties":{"value":{原schema}}} 包裹 */
    private static ObjectNode wrapInObject(ObjectNode original) {
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("type", "object");
        ObjectNode props = wrapper.putObject("properties");
        props.set("value", original);
        if (original.has("description")) {
            wrapper.set("description", original.get("description"));
        }
        return wrapper;
    }

    /** 追加 description 文本（与已有 description 合并） */
    private static void appendDescription(ObjectNode node, String text) {
        JsonNode existing = node.get("description");
        String prefix = existing != null && existing.isTextual()
            ? existing.asText() + "\n" : "";
        node.put("description", prefix + text);
    }

    /** 简明总结一个 Schema 节点 */
    private static String summarizeSchema(JsonNode schema, int maxLen) {
        if (schema == null || !schema.isObject()) return String.valueOf(schema);
        StringBuilder sb = new StringBuilder("{");
        var fields = ((ObjectNode) schema).fields();
        int count = 0;
        while (fields.hasNext() && sb.length() < maxLen) {
            var e = fields.next();
            String key = e.getKey();
            if (key.startsWith("$")) continue;
            if (count > 0) sb.append(", ");
            sb.append(key).append(": ");
            JsonNode val = e.getValue();
            if (val.isTextual()) sb.append('"').append(val.asText()).append('"');
            else if (val.isArray()) sb.append("[...]");
            else if (val.isObject()) sb.append("{...}");
            else sb.append(val.asText());
            count++;
        }
        sb.append("}");
        if (sb.length() > maxLen) { sb.setLength(maxLen); sb.append("...}"); }
        return sb.toString();
    }
}
