package com.clawkit.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityRedactorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldSummarizeTaskByTruncatingTo160Chars() {
        String shortPrompt = "Fix the bug in login";
        assertThat(ObservabilityRedactor.summarizeTask(shortPrompt))
            .isEqualTo("Fix the bug in login");

        String longPrompt = "A".repeat(200);
        String result = ObservabilityRedactor.summarizeTask(longPrompt);
        assertThat(result).hasSize(160);
        assertThat(result).endsWith("...");
    }

    @Test
    void shouldSummarizeTaskRemovingNewlines() {
        String prompt = "Fix\nthe  bug\r\nin   login";
        String result = ObservabilityRedactor.summarizeTask(prompt);
        assertThat(result).isEqualTo("Fix the bug in login");
    }

    @Test
    void shouldSummarizeTaskReturningEmptyForNull() {
        assertThat(ObservabilityRedactor.summarizeTask(null)).isEmpty();
        assertThat(ObservabilityRedactor.summarizeTask("")).isEmpty();
        assertThat(ObservabilityRedactor.summarizeTask("   ")).isEmpty();
    }

    @Test
    void shouldRedactSensitiveKeysInArguments() throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("filePath", "/tmp/test.txt");
        args.put("apiKey", "sk-secret-12345");
        args.put("content", "hello world");

        String summary = ObservabilityRedactor.summarizeArguments(args);

        assertThat(summary).contains("filePath");
        assertThat(summary).contains("/tmp/test.txt");
        assertThat(summary).contains("apiKey");
        assertThat(summary).contains("[REDACTED]");
        assertThat(summary).doesNotContain("sk-secret-12345");
    }

    @Test
    void shouldRedactTokenAndPasswordKeys() throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("token", "abc123");
        args.put("password", "supersecret");
        args.put("authorization", "Bearer xyz");
        args.put("name", "bob");

        String summary = ObservabilityRedactor.summarizeArguments(args);

        assertThat(summary).doesNotContain("abc123");
        assertThat(summary).doesNotContain("supersecret");
        assertThat(summary).doesNotContain("Bearer xyz");
        assertThat(summary).contains("bob");
        // 3 redacted fields: count [REDACTED] occurrences in single-line JSON
        int redactedCount = summary.length() - summary.replace("[REDACTED]", "").length();
        assertThat(redactedCount / "[REDACTED]".length()).isEqualTo(3);
    }

    @Test
    void shouldTruncateArgumentsTo256Chars() throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("veryLongKey", "X".repeat(500));

        String summary = ObservabilityRedactor.summarizeArguments(args);
        assertThat(summary.length()).isLessThanOrEqualTo(256);
    }

    @Test
    void shouldReturnEmptyForNullArguments() {
        assertThat(ObservabilityRedactor.summarizeArguments(null)).isEmpty();
    }

    @Test
    void shouldTruncateErrorMessageTo1024Chars() {
        String shortError = "Connection refused";
        assertThat(ObservabilityRedactor.summarizeError(shortError))
            .isEqualTo("Connection refused");

        String longError = "E".repeat(2000);
        String result = ObservabilityRedactor.summarizeError(longError);
        assertThat(result.length()).isLessThanOrEqualTo(1024);
        assertThat(result).endsWith("...");
    }

    @Test
    void shouldReturnNullForNullError() {
        assertThat(ObservabilityRedactor.summarizeError(null)).isNull();
    }

    @Test
    void shouldDetectSensitiveKeys() {
        assertThat(ObservabilityRedactor.isSensitiveKey("apiKey")).isTrue();
        assertThat(ObservabilityRedactor.isSensitiveKey("api_key")).isTrue();
        assertThat(ObservabilityRedactor.isSensitiveKey("token")).isTrue();
        assertThat(ObservabilityRedactor.isSensitiveKey("password")).isTrue();
        assertThat(ObservabilityRedactor.isSensitiveKey("secret")).isTrue();
        assertThat(ObservabilityRedactor.isSensitiveKey("authorization")).isTrue();
        assertThat(ObservabilityRedactor.isSensitiveKey("webhook")).isTrue();
        assertThat(ObservabilityRedactor.isSensitiveKey("bearer")).isTrue();
        assertThat(ObservabilityRedactor.isSensitiveKey("credential")).isTrue();
        assertThat(ObservabilityRedactor.isSensitiveKey("cookie")).isTrue();
        assertThat(ObservabilityRedactor.isSensitiveKey("privateKey")).isTrue();
        assertThat(ObservabilityRedactor.isSensitiveKey("private_key")).isTrue();
    }

    @Test
    void shouldNotDetectNonSensitiveKeys() {
        assertThat(ObservabilityRedactor.isSensitiveKey("filePath")).isFalse();
        assertThat(ObservabilityRedactor.isSensitiveKey("content")).isFalse();
        assertThat(ObservabilityRedactor.isSensitiveKey("name")).isFalse();
        assertThat(ObservabilityRedactor.isSensitiveKey("toolCallId")).isFalse();
        assertThat(ObservabilityRedactor.isSensitiveKey(null)).isFalse();
    }

    @Test
    void shouldHandleNestedObjectsInArguments() throws Exception {
        ObjectNode inner = MAPPER.createObjectNode();
        inner.put("token", "nested-secret");
        inner.put("path", "/tmp");

        ObjectNode args = MAPPER.createObjectNode();
        args.set("config", inner);
        args.put("name", "test");

        String summary = ObservabilityRedactor.summarizeArguments(args);

        assertThat(summary).doesNotContain("nested-secret");
        assertThat(summary).contains("[REDACTED]");
        assertThat(summary).contains("/tmp");
    }

    @Test
    void shouldHandleCaseInsensitiveSensitiveKeys() throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("APIKEY", "UPPER-SECRET");
        args.put("ApiKey", "MIXED-SECRET");

        String summary = ObservabilityRedactor.summarizeArguments(args);

        assertThat(summary).doesNotContain("UPPER-SECRET");
        assertThat(summary).doesNotContain("MIXED-SECRET");
    }

    @Test
    void shouldHandleKeysWithSeparators() throws Exception {
        // api-key, api_key, api.key should all be detected
        ObjectNode args = MAPPER.createObjectNode();
        args.put("api-key", "dash-secret");
        args.put("api.key", "dot-secret");

        String summary = ObservabilityRedactor.summarizeArguments(args);

        assertThat(summary).doesNotContain("dash-secret");
        assertThat(summary).doesNotContain("dot-secret");
    }
}
