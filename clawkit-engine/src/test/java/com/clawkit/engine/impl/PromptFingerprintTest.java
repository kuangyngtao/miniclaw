package com.clawkit.engine.impl;

import com.clawkit.provider.ModelRequest;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptFingerprintTest {

    @Test
    void isStableAcrossToolAndSchemaMapOrdering() {
        Map<String, Object> firstSchema = new LinkedHashMap<>();
        firstSchema.put("type", "object");
        firstSchema.put("properties", Map.of("path", Map.of("type", "string")));
        Map<String, Object> secondSchema = new LinkedHashMap<>();
        secondSchema.put("properties", Map.of("path", Map.of("type", "string")));
        secondSchema.put("type", "object");
        var first = ModelRequest.of(List.of(Message.system("stable"), Message.user("variable-a")),
            List.of(new ToolDefinition("z", "z", Map.of()),
                new ToolDefinition("a", "a", firstSchema)));
        var second = ModelRequest.of(List.of(Message.system("stable"), Message.user("variable-b")),
            List.of(new ToolDefinition("a", "a", secondSchema),
                new ToolDefinition("z", "z", Map.of())));

        assertThat(PromptFingerprint.compute(first)).isEqualTo(PromptFingerprint.compute(second));
    }

    @Test
    void changesWhenStablePrefixChanges() {
        var first = ModelRequest.of(List.of(Message.system("stable-a")), List.of());
        var second = ModelRequest.of(List.of(Message.system("stable-b")), List.of());

        assertThat(PromptFingerprint.compute(first)).isNotEqualTo(PromptFingerprint.compute(second));
    }
}
