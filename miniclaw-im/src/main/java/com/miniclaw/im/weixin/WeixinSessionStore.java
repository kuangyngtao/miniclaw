package com.miniclaw.im.weixin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WeixinSessionStore {

    private static final Logger log = LoggerFactory.getLogger(WeixinSessionStore.class);
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path STORE_PATH = Path.of(
        System.getProperty("user.home"), ".miniclaw", "weixin-session.json");

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Session(
        String botToken,
        String baseUrl,
        String ilinkBotId,
        String ilinkUserId
    ) {}

    static Session load() {
        if (!Files.isRegularFile(STORE_PATH)) return null;
        try {
            Session s = JSON.readValue(STORE_PATH.toFile(), Session.class);
            if (s.botToken == null || s.botToken.isBlank()) {
                log.info("WeChat session has no bot_token, ignoring");
                return null;
            }
            log.info("WeChat session loaded from disk");
            return s;
        } catch (IOException e) {
            log.warn("Failed to load WeChat session: {}", e.getMessage());
            return null;
        }
    }

    static void save(String botToken, String baseUrl, String ilinkBotId, String ilinkUserId) {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            JSON.writeValue(STORE_PATH.toFile(),
                new Session(botToken, baseUrl, ilinkBotId, ilinkUserId));
            log.info("WeChat session saved to {}", STORE_PATH);
        } catch (IOException e) {
            log.warn("Failed to save WeChat session: {}", e.getMessage());
        }
    }

    static void clear() {
        try {
            Files.deleteIfExists(STORE_PATH);
            log.info("WeChat session cleared");
        } catch (IOException e) {
            log.warn("Failed to clear WeChat session: {}", e.getMessage());
        }
    }
}
