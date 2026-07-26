package com.clawkit.im.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feishu Open API client using bot / tenant_access_token identity.
 *
 * <p>R4: Uses ObjectNode for request construction, validates all inputs,
 * enforces request timeout, throws structured {@link FeishuApiException}.
 */
public class FeishuApi {

    private static final Logger log = LoggerFactory.getLogger(FeishuApi.class);
    private static final String BASE = "https://open.feishu.cn/open-apis";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_CONTENT_LENGTH = 30000;
    private static final Pattern CHAT_ID_PATTERN = Pattern.compile("^oc_[a-zA-Z0-9]+$");
    private static final Pattern MSG_ID_PATTERN = Pattern.compile("^om_[a-zA-Z0-9]+$");
    private static final Pattern UUID_PATTERN =
        Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final HttpClient http;
    private final String appId;
    private final String appSecret;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt;

    public FeishuApi(String appId, String appSecret) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    // ── Token ──

    synchronized String getToken() throws IOException, InterruptedException {
        if (cachedToken != null && tokenExpiresAt != null
            && Instant.now().isBefore(tokenExpiresAt.minusSeconds(300))) {
            return cachedToken;
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);
        JsonNode json = post(BASE + "/auth/v3/tenant_access_token/internal", body, false);
        int code = json.path("code").asInt(-1);
        if (code != 0) {
            throw new FeishuApiException(200, code, json.path("msg").asText());
        }
        cachedToken = json.path("tenant_access_token").asText();
        int expire = json.path("expire").asInt(7200);
        tokenExpiresAt = Instant.now().plusSeconds(expire);
        log.info("Feishu token refreshed, expires in {}s", expire);
        return cachedToken;
    }

    // ── Validation ──

    private static void validateChatId(String chatId) {
        if (chatId == null || !CHAT_ID_PATTERN.matcher(chatId).matches())
            throw new IllegalArgumentException("invalid chat_id format: " + safe(chatId));
    }

    private static void validateMessageId(String messageId) {
        if (messageId == null || !MSG_ID_PATTERN.matcher(messageId).matches())
            throw new IllegalArgumentException("invalid message_id format: " + safe(messageId));
    }

    private static void validateUuid(String uuid) {
        if (uuid == null || !UUID_PATTERN.matcher(uuid).matches())
            throw new IllegalArgumentException("invalid uuid format: " + safe(uuid));
    }

    private static void validateContent(String content) {
        if (content == null || content.isBlank())
            throw new IllegalArgumentException("content must not be empty");
        if (content.length() > MAX_CONTENT_LENGTH)
            throw new IllegalArgumentException("content too long: " + content.length());
    }

    private static String safe(String s) {
        if (s == null) return "null";
        return s.length() > 20 ? s.substring(0, 17) + "..." : s;
    }

    // ── Send to chat ──

    /**
     * Send a text message to a fixed chat_id.
     *
     * @param chatId fixed chat_id (bot must be a member)
     * @param content message text
     * @param idempotencyKey stable UUID for dedup
     * @return Feishu message_id
     */
    public String sendChatMessage(String chatId, String content, String idempotencyKey)
        throws IOException, InterruptedException {
        validateChatId(chatId);
        validateContent(content);
        validateUuid(idempotencyKey);

        ObjectNode textContent = JSON.createObjectNode();
        textContent.put("text", content);
        String contentStr = JSON.writeValueAsString(textContent);

        ObjectNode body = JSON.createObjectNode();
        body.put("receive_id", chatId);
        body.put("msg_type", "text");
        body.put("content", contentStr);
        body.put("uuid", idempotencyKey);

        JsonNode json = post(BASE + "/im/v1/messages?receive_id_type=chat_id", body, true);
        return json.path("data").path("message_id").asText();
    }

    // ── Reply to message ──

    /**
     * Reply to an existing message (thread reply).
     *
     * @param messageId root message to reply to
     * @param content reply text
     * @param idempotencyKey stable UUID for dedup
     * @return reply message_id
     */
    public String replyMessage(String messageId, String content, String idempotencyKey)
        throws IOException, InterruptedException {
        validateMessageId(messageId);
        validateContent(content);
        validateUuid(idempotencyKey);

        ObjectNode textContent = JSON.createObjectNode();
        textContent.put("text", content);
        String contentStr = JSON.writeValueAsString(textContent);

        ObjectNode body = JSON.createObjectNode();
        body.put("msg_type", "text");
        body.put("content", contentStr);
        body.put("uuid", idempotencyKey);

        JsonNode json = post(BASE + "/im/v1/messages/" + messageId + "/reply", body, true);
        return json.path("data").path("message_id").asText();
    }

    // ── Legacy methods (keep for backward compat) ──

    public String sendMessage(String openId, String text) throws IOException, InterruptedException {
        ObjectNode textContent = JSON.createObjectNode(); textContent.put("text", text);
        ObjectNode body = JSON.createObjectNode();
        body.put("receive_id", openId);
        body.put("msg_type", "text");
        body.put("content", JSON.writeValueAsString(textContent));
        JsonNode json = post(BASE + "/im/v1/messages?receive_id_type=open_id", body, true);
        return json.path("data").path("message_id").asText();
    }

    public void editMessage(String messageId, String text) throws IOException, InterruptedException {
        ObjectNode textContent = JSON.createObjectNode(); textContent.put("text", text);
        ObjectNode body = JSON.createObjectNode();
        body.put("msg_type", "text");
        body.put("content", JSON.writeValueAsString(textContent));
        String url = BASE + "/im/v1/messages/" + messageId;
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + getToken())
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .timeout(DEFAULT_TIMEOUT).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = JSON.readTree(resp.body());
            int code = json.path("code").asInt(-1);
            if (code != 0) log.warn("Feishu edit error: {} (msg_id={})", json.path("msg").asText(), messageId);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }

    // ── Internal HTTP ──

    private JsonNode post(String url, ObjectNode body, boolean auth)
        throws IOException, InterruptedException {
        String bodyStr = JSON.writeValueAsString(body);
        var builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
            .timeout(DEFAULT_TIMEOUT);
        if (auth) builder.header("Authorization", "Bearer " + getToken());

        HttpResponse<String> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new FeishuApiException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }

        JsonNode json;
        try { json = JSON.readTree(resp.body()); }
        catch (Exception e) { throw new FeishuApiException("invalid JSON response", e); }

        int httpStatus = resp.statusCode();
        int apiCode = json.path("code").asInt(-1);

        if (httpStatus != 200 || apiCode != 0) {
            throw new FeishuApiException(httpStatus, apiCode, json.path("msg").asText());
        }
        return json;
    }
}
