package com.clawkit.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves CLI, environment, user YAML and defaults without persisting credentials. */
final class ConfigResolver {
    static final String DEFAULT_MODEL = "deepseek-chat";
    static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    static final String DEFAULT_PROTOCOL = "OPENAI_COMPAT";
    private static final Set<String> SECRET_FIELDS = Set.of(
        "apikey", "api_key", "token", "secret", "authorization", "appsecret");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ConfigResolver() {}

    static ResolvedConfiguration resolve(String cliModel, String cliBaseUrl, String cliProtocol,
                                         boolean thinking, Path rootDir,
                                         Map<String, String> env, Path userHome) {
        JsonNode file = load(userHome.resolve(".clawkit").resolve("config.yaml"));
        rejectSecrets(file, "");
        validateKnownFields(file);
        JsonNode provider = file.path("provider");

        Map<String, String> sources = new LinkedHashMap<>();
        String model = choose(cliModel, env.get("CLAWKIT_MODEL"), text(provider, "model"),
            DEFAULT_MODEL, "model", sources);
        String baseUrl = choose(cliBaseUrl, null, text(provider, "baseUrl"),
            DEFAULT_BASE_URL, "baseUrl", sources);
        String protocol = choose(cliProtocol, null, text(provider, "protocol"),
            DEFAULT_PROTOCOL, "protocol", sources);
        int timeout = chooseInt(env.get("CLAWKIT_REQUEST_TIMEOUT_SECONDS"), provider.get("requestTimeoutSeconds"),
            60, "requestTimeoutSeconds", sources, 1, 600);
        int retries = chooseInt(env.get("CLAWKIT_MAX_RETRIES"), provider.get("maxRetries"),
            3, "maxRetries", sources, 0, 10);

        validate(model, baseUrl, protocol);
        String apiKey = trim(env.get("CLAWKIT_API_KEY"));
        boolean effectiveThinking = thinking || chooseBoolean(
            env.get("CLAWKIT_THINKING"), file.path("runtime").get("thinking"), false);
        sources.put("thinking", thinking ? "cli"
            : env.containsKey("CLAWKIT_THINKING") ? "env"
            : file.path("runtime").has("thinking") ? "config" : "default");
        EffectiveConfig effective = new EffectiveConfig(model, normalizeBaseUrl(baseUrl),
            DEFAULT_PROTOCOL, timeout, retries, effectiveThinking, rootDir,
            apiKey != null, sources);
        return new ResolvedConfiguration(effective, apiKey);
    }

    private static JsonNode load(Path file) {
        if (!Files.exists(file)) return YAML.createObjectNode();
        try {
            JsonNode root = YAML.readTree(file.toFile());
            return root == null ? YAML.createObjectNode() : root;
        } catch (Exception e) {
            throw new ConfigurationException("C-002", "Cannot parse " + file,
                "clawkit was not started", "Fix the YAML syntax or move the file aside.");
        }
    }

    private static void rejectSecrets(JsonNode node, String path) {
        if (node == null || !node.isContainerNode()) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                String child = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                if (SECRET_FIELDS.contains(key)) {
                    throw new ConfigurationException("C-005",
                        "Plaintext credential field is forbidden: " + child,
                        "clawkit was not started",
                        "Remove the field and set CLAWKIT_API_KEY in the environment.");
                }
                rejectSecrets(entry.getValue(), child);
            });
        } else {
            node.forEach(child -> rejectSecrets(child, path));
        }
    }

    private static void validateKnownFields(JsonNode root) {
        if (!root.isObject()) {
            throw new ConfigurationException("C-002", "config.yaml root must be an object",
                "clawkit was not started", "Use the structure from examples/config.example.yaml.");
        }
        rejectUnknown(root, Set.of("provider", "runtime", "feishu", "weixin"), "");
        rejectUnknown(root.path("provider"), Set.of("model", "baseUrl", "protocol",
            "requestTimeoutSeconds", "maxRetries"), "provider");
        rejectUnknown(root.path("runtime"), Set.of("thinking"), "runtime");
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String path) {
        if (node == null || node.isMissingNode()) return;
        if (!node.isObject()) {
            throw new ConfigurationException("C-002", path + " must be an object",
                "clawkit was not started", "Use the documented YAML structure.");
        }
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                String full = path.isEmpty() ? name : path + "." + name;
                throw new ConfigurationException("C-002", "Unknown configuration field: " + full,
                    "clawkit was not started", "Remove the field or use a documented setting.");
            }
        });
    }

    private static void validate(String model, String baseUrl, String protocol) {
        if (!model.startsWith("deepseek-")) {
            throw new ConfigurationException("C-004", "Unsupported model: " + model,
                "the provider was not created", "Use deepseek-chat or deepseek-reasoner.");
        }
        if (!DEFAULT_PROTOCOL.equalsIgnoreCase(protocol)) {
            throw new ConfigurationException("C-004", "Unsupported protocol: " + protocol,
                "the provider was not created", "Use OPENAI_COMPAT for DeepSeek.");
        }
        try {
            URI uri = URI.create(baseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"api.deepseek.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw new ConfigurationException("C-006", "Untrusted DeepSeek endpoint: " + baseUrl,
                "the API key was not sent", "Use https://api.deepseek.com.");
        }
    }

    private static String normalizeBaseUrl(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String choose(String cli, String env, String file, String fallback,
                                 String key, Map<String, String> sources) {
        String value = trim(cli);
        if (value != null) { sources.put(key, "cli"); return value; }
        value = trim(env);
        if (value != null) { sources.put(key, "env"); return value; }
        value = trim(file);
        if (value != null) { sources.put(key, "config"); return value; }
        sources.put(key, "default");
        return fallback;
    }

    private static int chooseInt(String env, JsonNode file, int fallback, String key,
                                 Map<String, String> sources, int min, int max) {
        String raw = trim(env);
        String source = "env";
        if (raw == null && file != null && !file.isMissingNode() && !file.isNull()) {
            raw = file.asText(); source = "config";
        }
        if (raw == null) { sources.put(key, "default"); return fallback; }
        try {
            int value = Integer.parseInt(raw);
            if (value < min || value > max) throw new NumberFormatException();
            sources.put(key, source);
            return value;
        } catch (NumberFormatException e) {
            throw new ConfigurationException("C-002", "Invalid " + key + ": " + raw,
                "clawkit was not started", "Use a value between " + min + " and " + max + ".");
        }
    }

    private static boolean chooseBoolean(String env, JsonNode file, boolean fallback) {
        String raw = trim(env);
        if (raw == null && file != null && !file.isNull()) raw = file.asText();
        if (raw == null) return fallback;
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        throw new ConfigurationException("C-002", "Invalid thinking value: " + raw,
            "clawkit was not started", "Use true or false.");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
