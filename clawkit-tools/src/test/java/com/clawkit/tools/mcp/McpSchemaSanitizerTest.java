package com.clawkit.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class McpSchemaSanitizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldStripMetaKeys() throws Exception {
        JsonNode raw = MAPPER.readTree("""
            {"type":"object","properties":{"path":{"type":"string","$ref":"#/$defs/Path"}},
             "$schema":"https://json-schema.org/draft/2020-12/schema",
             "$defs":{"Path":{"type":"string"}},"$id":"foo"}""");

        String result = McpSchemaSanitizer.sanitize(raw);
        JsonNode node = MAPPER.readTree(result);

        assertThat(node.has("$schema")).isFalse();
        assertThat(node.has("$defs")).isFalse();
        assertThat(node.has("$id")).isFalse();
        assertThat(node.get("properties").get("path").has("$ref")).isFalse();
        assertThat(node.get("properties").get("path").get("type").asText()).isEqualTo("string");
    }

    @Test
    void shouldResolveAnyOfIntoDescription() throws Exception {
        JsonNode raw = MAPPER.readTree("""
            {"type":"object","properties":{"path":{"type":"string"}},
             "anyOf":[{"required":["path"]},{"required":["url"]}]}""");

        String result = McpSchemaSanitizer.sanitize(raw);
        JsonNode node = MAPPER.readTree(result);

        assertThat(node.has("anyOf")).isFalse();
        assertThat(node.get("description").asText()).contains("[anyOf]", "Option 1", "Option 2");
    }

    @Test
    void shouldResolveOneOfIntoDescription() throws Exception {
        JsonNode raw = MAPPER.readTree("""
            {"type":"object","oneOf":[{"type":"string"},{"type":"number"}]}""");

        String result = McpSchemaSanitizer.sanitize(raw);
        JsonNode node = MAPPER.readTree(result);

        assertThat(node.has("oneOf")).isFalse();
        assertThat(node.get("description").asText()).contains("[oneOf]", "Option 1", "Option 2");
    }

    @Test
    void shouldEnsureTypeAndProperties() throws Exception {
        JsonNode raw = MAPPER.readTree("{}");

        String result = McpSchemaSanitizer.sanitize(raw);
        JsonNode node = MAPPER.readTree(result);

        assertThat(node.get("type").asText()).isEqualTo("object");
        assertThat(node.has("properties")).isTrue();
    }

    @Test
    void shouldTruncateOverlyLongDescription() throws Exception {
        String longDesc = "x".repeat(1200);
        JsonNode raw = MAPPER.readTree("{\"type\":\"object\",\"properties\":{},\"description\":\"" + longDesc + "\"}");

        String result = McpSchemaSanitizer.sanitize(raw);
        JsonNode node = MAPPER.readTree(result);

        assertThat(node.get("description").asText()).hasSizeLessThanOrEqualTo(1003);
        assertThat(node.get("description").asText()).endsWith("...");
    }

    @Test
    void shouldWrapNonObjectType() throws Exception {
        JsonNode raw = MAPPER.readTree("{\"type\":\"string\",\"description\":\"a string value\"}");

        String result = McpSchemaSanitizer.sanitize(raw);
        JsonNode node = MAPPER.readTree(result);

        assertThat(node.get("type").asText()).isEqualTo("object");
        assertThat(node.get("properties").has("value")).isTrue();
        assertThat(node.get("properties").get("value").get("type").asText()).isEqualTo("string");
    }

    @Test
    void shouldPreserveExistingDescriptionWhenAppendingAnyOf() throws Exception {
        JsonNode raw = MAPPER.readTree("""
            {"type":"object","properties":{},"description":"select input",
             "anyOf":[{"required":["file"]}]}""");

        String result = McpSchemaSanitizer.sanitize(raw);
        JsonNode node = MAPPER.readTree(result);

        assertThat(node.get("description").asText()).startsWith("select input");
        assertThat(node.get("description").asText()).contains("[anyOf]");
    }

    @Test
    void shouldHandleNormalSchemaWithoutChanges() throws Exception {
        JsonNode raw = MAPPER.readTree("""
            {"type":"object","properties":{"path":{"type":"string","description":"file path"},
             "content":{"type":"string","description":"file contents"}},
             "required":["path"]}""");

        String result = McpSchemaSanitizer.sanitize(raw);
        JsonNode node = MAPPER.readTree(result);

        assertThat(node.get("type").asText()).isEqualTo("object");
        assertThat(node.get("properties").get("path").get("type").asText()).isEqualTo("string");
    }
}
