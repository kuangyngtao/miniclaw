package com.clawkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigResolverTest {
    @TempDir Path home;

    @Test
    void resolvesCliThenEnvThenYamlThenDefaults() throws Exception {
        writeConfig("""
            provider:
              model: deepseek-reasoner
              requestTimeoutSeconds: 90
              maxRetries: 2
            runtime:
              thinking: true
            """);
        Map<String, String> env = new HashMap<>();
        env.put("CLAWKIT_API_KEY", "sk-test");
        env.put("CLAWKIT_MODEL", "deepseek-chat");
        env.put("CLAWKIT_MAX_RETRIES", "4");

        ResolvedConfiguration resolved = ConfigResolver.resolve(
            "deepseek-reasoner", null, null, false, null, env, home);

        assertThat(resolved.effective().model()).isEqualTo("deepseek-reasoner");
        assertThat(resolved.effective().requestTimeoutSeconds()).isEqualTo(90);
        assertThat(resolved.effective().maxRetries()).isEqualTo(4);
        assertThat(resolved.effective().sources()).containsEntry("model", "cli")
            .containsEntry("requestTimeoutSeconds", "config")
            .containsEntry("maxRetries", "env")
            .containsEntry("baseUrl", "default");
        assertThat(resolved.effective().apiKeyConfigured()).isTrue();
        assertThat(resolved.effective().thinking()).isTrue();
        assertThat(resolved.toString()).doesNotContain("sk-test");
    }

    @Test
    void rejectsPlaintextCredentialAtAnyDepth() throws Exception {
        writeConfig("provider:\n  apiKey: sk-forbidden\n");
        assertThatThrownBy(() -> ConfigResolver.resolve(null, null, null, false, null,
            Map.of(), home))
            .isInstanceOf(ConfigurationException.class)
            .extracting(e -> ((ConfigurationException) e).code())
            .isEqualTo("C-005");
    }

    @Test
    void rejectsMalformedYamlAndUntrustedEndpoint() throws Exception {
        writeConfig("provider: [unterminated");
        assertThatThrownBy(() -> ConfigResolver.resolve(null, null, null, false, null,
            Map.of(), home)).isInstanceOf(ConfigurationException.class)
            .extracting(e -> ((ConfigurationException) e).code()).isEqualTo("C-002");

        writeConfig("provider:\n  baseUrl: https://example.com\n");
        assertThatThrownBy(() -> ConfigResolver.resolve(null, null, null, false, null,
            Map.of(), home)).isInstanceOf(ConfigurationException.class)
            .extracting(e -> ((ConfigurationException) e).code()).isEqualTo("C-006");
    }

    @Test
    void validatesNumericBounds() throws Exception {
        writeConfig("provider:\n  maxRetries: 99\n");
        assertThatThrownBy(() -> ConfigResolver.resolve(null, null, null, false, null,
            Map.of(), home)).isInstanceOf(ConfigurationException.class)
            .extracting(e -> ((ConfigurationException) e).code()).isEqualTo("C-002");
    }

    @Test
    void rejectsUnknownFieldsAndInvalidBooleans() throws Exception {
        writeConfig("provider:\n  typo: value\n");
        assertThatThrownBy(() -> ConfigResolver.resolve(null, null, null, false, null,
            Map.of(), home)).isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("provider.typo");

        writeConfig("runtime:\n  thinking: sometimes\n");
        assertThatThrownBy(() -> ConfigResolver.resolve(null, null, null, false, null,
            Map.of(), home)).isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("Invalid thinking");
    }

    private void writeConfig(String content) throws Exception {
        Path dir = home.resolve(".clawkit");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yaml"), content);
    }
}
