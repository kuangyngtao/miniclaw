package com.miniclaw.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConfigHelper {

    private static final Logger log = LoggerFactory.getLogger(ConfigHelper.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ConfigHelper() {}

    public static String readConfig(String... path) {
        try {
            Path configFile = Path.of(System.getProperty("user.home"), ".miniclaw", "config.yaml");
            if (!Files.exists(configFile)) return null;
            JsonNode node = YAML.readTree(configFile.toFile());
            for (String key : path) {
                node = node.path(key);
                if (node.isMissingNode()) return null;
            }
            String val = node.asText();
            return val.isBlank() ? null : val;
        } catch (IOException e) {
            log.debug("Could not read config.yaml: {}", e.getMessage());
            return null;
        }
    }
}
