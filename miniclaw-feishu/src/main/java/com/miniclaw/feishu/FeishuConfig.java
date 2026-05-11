package com.miniclaw.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feishu bot configuration.
 *
 * Priority: environment variables > ~/.miniclaw/config.yaml
 *
 * @param appId     Feishu app ID
 * @param appSecret Feishu app secret
 * @param port      HTTP listen port (default 8080)
 * @param workDir   working directory for AgentEngine
 * @param publicUrl optional public-facing URL (for startup banner)
 */
public record FeishuConfig(
    String appId,
    String appSecret,
    int port,
    String workDir,
    String publicUrl
) {
    private static final Logger log = LoggerFactory.getLogger(FeishuConfig.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static FeishuConfig fromEnv() {
        String appId = envOrConfig("FEISHU_APP_ID", "feishu", "appId");
        String appSecret = envOrConfig("FEISHU_APP_SECRET", "feishu", "appSecret");

        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException(
                "[C-003] FEISHU_APP_ID and FEISHU_APP_SECRET must be set (env or ~/.miniclaw/config.yaml).");
        }

        int port = 8080;
        String portStr = envOrConfig("FEISHU_PORT", "feishu", "port");
        if (portStr != null && !portStr.isBlank()) {
            try {
                port = Integer.parseInt(portStr.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                    "[C-003] FEISHU_PORT must be a valid integer, got: " + portStr);
            }
        }

        String workDir = System.getProperty("user.home");
        String workDirEnv = System.getenv("FEISHU_WORKDIR");
        if (workDirEnv != null && !workDirEnv.isBlank()) {
            workDir = workDirEnv;
        }

        String publicUrl = System.getenv("FEISHU_PUBLIC_URL");

        return new FeishuConfig(appId, appSecret, port, workDir, publicUrl);
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private static String envOrConfig(String envKey, String... configPath) {
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) {
            return envVal;
        }
        return readConfig(configPath);
    }

    /** Read a nested value from ~/.miniclaw/config.yaml. Returns null if not found. */
    public static String readConfig(String... path) {
        try {
            Path configFile = Path.of(System.getProperty("user.home"), ".miniclaw", "config.yaml");
            if (!Files.exists(configFile)) {
                return null;
            }
            JsonNode node = YAML.readTree(configFile.toFile());
            for (String key : path) {
                node = node.path(key);
                if (node.isMissingNode()) {
                    return null;
                }
            }
            String val = node.asText();
            return val.isBlank() ? null : val;
        } catch (IOException e) {
            log.debug("Could not read config.yaml: {}", e.getMessage());
            return null;
        }
    }
}
