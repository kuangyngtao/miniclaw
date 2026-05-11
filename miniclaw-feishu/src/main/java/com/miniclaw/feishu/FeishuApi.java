package com.miniclaw.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal Feishu Open API client.
 * Handles tenant_access_token lifecycle, send message, and edit message.
 */
class FeishuApi {

    private static final Logger log = LoggerFactory.getLogger(FeishuApi.class);
    private static final String BASE = "https://open.feishu.cn/open-apis";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String appId;
    private final String appSecret;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt;

    FeishuApi(String appId, String appSecret) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    // ─── Token ───────────────────────────────────────────────────────

    synchronized String getToken() throws IOException, InterruptedException {
        if (cachedToken != null && tokenExpiresAt != null
            && Instant.now().isBefore(tokenExpiresAt.minusSeconds(300))) {
            return cachedToken;
        }

        String body = String.format("{\"app_id\":\"%s\",\"app_secret\":\"%s\"}", appId, appSecret);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/auth/v3/tenant_access_token/internal"))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = JSON.readTree(resp.body());

        int code = json.path("code").asInt(-1);
        if (code != 0) {
            throw new IOException("Feishu token error: " + json.path("msg").asText());
        }

        cachedToken = json.path("tenant_access_token").asText();
        int expire = json.path("expire").asInt(7200);
        tokenExpiresAt = Instant.now().plusSeconds(expire);

        log.info("Feishu token refreshed, expires in {}s", expire);
        return cachedToken;
    }

    // ─── Send Message ────────────────────────────────────────────────

    /**
     * Send a text message to a user. Returns the message_id.
     */
    String sendMessage(String openId, String text) throws IOException, InterruptedException {
        String contentJson = "{\"text\":" + JSON.writeValueAsString(text) + "}";
        String body = String.format(
            "{\"receive_id\":\"%s\",\"msg_type\":\"text\",\"content\":%s}",
            openId, JSON.writeValueAsString(contentJson));

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/im/v1/messages?receive_id_type=open_id"))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Authorization", "Bearer " + getToken())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = JSON.readTree(resp.body());

        int code = json.path("code").asInt(-1);
        if (code != 0) {
            throw new IOException("Feishu send error: " + json.path("msg").asText());
        }

        return json.path("data").path("message_id").asText();
    }

    // ─── Edit Message ────────────────────────────────────────────────

    /**
     * Edit (update) the text content of an existing message.
     */
    void editMessage(String messageId, String text) throws IOException, InterruptedException {
        String contentJson = "{\"text\":" + JSON.writeValueAsString(text) + "}";
        String body = "{\"msg_type\":\"text\",\"content\":" + JSON.writeValueAsString(contentJson) + "}";

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/im/v1/messages/" + messageId))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Authorization", "Bearer " + getToken())
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = JSON.readTree(resp.body());

        int code = json.path("code").asInt(-1);
        if (code != 0) {
            log.warn("Feishu edit error: {} (msg_id={})", json.path("msg").asText(), messageId);
        }
    }
}
