package com.clawkit.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MCP 配置加载器 — 两级配置合并 + 环境变量替换。 */
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);
    private static final ObjectMapper JSON = new JsonMapper();

    private final Map<String, McpServerConfig> servers;

    public McpConfig(Map<String, McpServerConfig> servers) {
        this.servers = Map.copyOf(servers);
    }

    public Map<String, McpServerConfig> servers() {
        return servers;
    }

    /** 加载配置：用户级 + 项目级合并，再做变量替换。 */
    public static McpConfig load(Path workDir) {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>();

        // 1. 用户级: ~/.clawkit/mcp.json
        Path userConfig = Path.of(System.getProperty("user.home"), ".clawkit", "mcp.json");
        if (Files.exists(userConfig)) {
            Map<String, McpServerConfig> userServers = parseFile(userConfig, workDir);
            merged.putAll(userServers);
            log.debug("[MCP] loaded {} servers from {}", userServers.size(), userConfig);
        }

        // 2. 项目级: .clawkit/mcp.json (覆盖用户级同 key)
        Path projectConfig = workDir.resolve(".clawkit").resolve("mcp.json");
        if (Files.exists(projectConfig)) {
            Map<String, McpServerConfig> projectServers = parseFile(projectConfig, workDir);
            merged.putAll(projectServers);  // 项目级覆盖
            log.debug("[MCP] loaded {} servers from {} (merged)", projectServers.size(), projectConfig);
        }

        return new McpConfig(merged);
    }

    private static Map<String, McpServerConfig> parseFile(Path file, Path workDir) {
        Map<String, McpServerConfig> result = new LinkedHashMap<>();
        try {
            JsonNode root = JSON.readTree(file.toFile());
            JsonNode serversNode = root.get("mcpServers");
            if (serversNode == null || !serversNode.isObject()) return result;

            var fields = serversNode.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String name = entry.getKey();
                JsonNode srv = entry.getValue();
                if (!srv.isObject()) continue;

                String command = srv.has("command") ? srv.get("command").asText() : null;
                List<String> args = readStringList(srv.get("args"));
                String url = srv.has("url") ? srv.get("url").asText() : null;
                Map<String, String> env = readEnv(srv.get("env"), workDir);
                boolean disabled = srv.has("disabled") && srv.get("disabled").asBoolean();

                // 变量替换
                if (args != null) args = args.stream().map(a -> resolveVars(a, workDir)).toList();
                if (env != null) {
                    Map<String, String> resolved = new LinkedHashMap<>();
                    env.forEach((k, v) -> resolved.put(k, resolveVars(v, workDir)));
                    env = resolved;
                }

                result.put(name, new McpServerConfig(name, command, args, url, env, disabled));
            }
        } catch (IOException e) {
            log.warn("[MCP] failed to parse {}: {}", file, e.getMessage());
        }
        return result;
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            list.add(item.asText());
        }
        return list;
    }

    private static Map<String, String> readEnv(JsonNode node, Path workDir) {
        if (node == null || !node.isObject()) return Map.of();
        Map<String, String> env = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            env.put(entry.getKey(), entry.getValue().asText());
        }
        return env;
    }

    static String resolveVars(String value, Path workDir) {
        if (value == null || !value.contains("${")) return value;

        value = value.replace("${PROJECT_DIR}", workDir.toAbsolutePath().toString());
        value = value.replace("${HOME}", System.getProperty("user.home"));

        // ${env:VAR_NAME} 和 ${env:VAR_NAME:-default}
        int i = 0;
        while ((i = value.indexOf("${env:", i)) >= 0) {
            int end = value.indexOf('}', i);
            if (end < 0) break;
            String expr = value.substring(i + 5, end);  // skip "${env:"
            String varName;
            String defaultVal = null;
            int colonDash = expr.indexOf(":-");
            if (colonDash >= 0) {
                varName = expr.substring(0, colonDash);
                defaultVal = expr.substring(colonDash + 2);
            } else {
                varName = expr;
            }
            String envVal = System.getenv(varName);
            String replacement = envVal != null ? envVal : (defaultVal != null ? defaultVal : "");
            value = value.substring(0, i) + replacement + value.substring(end + 1);
            i += replacement.length();
        }
        return value;
    }
}
