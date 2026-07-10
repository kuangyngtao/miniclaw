package com.clawkit.im.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WeixinIlinkClient {

    private static final Logger log = LoggerFactory.getLogger(WeixinIlinkClient.class);
    private static final String DEFAULT_BASE = "https://ilinkai.weixin.qq.com/ilink/bot";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_POLL_TIMEOUT_S = 35;
    private static final int AGENT_POLL_TIMEOUT_S = 3;
    private static final int MAX_DEDUP_IDS = 10_000;

    private final HttpClient http;
    private final boolean debug;
    private final Set<Long> seenMsgIds = ConcurrentHashMap.newKeySet();

    private String baseUrl = DEFAULT_BASE;
    private volatile String botToken;
    private volatile String ilinkBotId;
    private volatile String ilinkUserId;
    private volatile String typingTicket;
    private volatile long typingTicketExpiresAtMs;
    private volatile int pollTimeoutS = DEFAULT_POLL_TIMEOUT_S;
    private volatile boolean agentBusy;
    private volatile String getUpdatesBuf = "";

    WeixinIlinkClient(boolean debug) {
        this.debug = debug;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    // ─── Agent busy state ───────────────────────────────────────────────

    void setAgentBusy(boolean busy) {
        this.agentBusy = busy;
    }

    String boundUserId() {
        return ilinkUserId;
    }

    String baseUrl() {
        return baseUrl;
    }

    // ─── QR code login ──────────────────────────────────────────────────

    record QRCode(String qrcode, String qrcodeUrl) {}

    /** GET /ilink/bot/get_bot_qrcode?bot_type=3 */
    QRCode getQRCode() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(DEFAULT_BASE + "/get_bot_qrcode?bot_type=3"))
            .GET()
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();
        log.info("get_bot_qrcode → [{}] body={}",
            resp.statusCode(), body != null ? body.substring(0, Math.min(500, body.length())) : "null");

        JsonNode json = JSON.readTree(body);
        int ret = json.path("ret").asInt(-1);
        if (ret != 0) {
            String errcode = json.path("errcode").asText("");
            String errmsg = json.path("errmsg").asText("");
            throw new IOException(String.format(
                "Failed to get QR code [ret=%d errcode=%s errmsg=%s] body=%s",
                ret, errcode, errmsg,
                body != null ? body.substring(0, Math.min(300, body.length())) : "null"));
        }

        String qrcode = json.path("qrcode").asText();
        String qrcodeUrl = json.path("qrcode_img_content").asText();
        return new QRCode(qrcode, qrcodeUrl);
    }

    enum QRStatus { WAIT, SCANNED, CONFIRMED, EXPIRED }

    record QRResult(QRStatus status, String botToken, String baseUrl,
                    String ilinkBotId, String ilinkUserId) {}

    /** GET /ilink/bot/get_qrcode_status?qrcode=xxx */
    QRResult pollQRCodeStatus(String qrcode) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(DEFAULT_BASE + "/get_qrcode_status?qrcode=" + qrcode))
            .GET()
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();
        log.debug("get_qrcode_status → [{}] body={}",
            resp.statusCode(), body != null ? body.substring(0, Math.min(300, body.length())) : "null");

        JsonNode json = JSON.readTree(body);
        int ret = json.path("ret").asInt(-1);
        if (ret != 0) {
            String errcode = json.path("errcode").asText("");
            String errmsg = json.path("errmsg").asText("");
            throw new IOException(String.format(
                "QR status error [ret=%d errcode=%s errmsg=%s] body=%s",
                ret, errcode, errmsg,
                body != null ? body.substring(0, Math.min(300, body.length())) : "null"));
        }

        String statusStr = json.path("status").asText("wait");
        QRStatus status = switch (statusStr) {
            case "confirmed" -> QRStatus.CONFIRMED;
            case "scaned", "scaned_but_redirect" -> QRStatus.SCANNED;
            case "expired" -> QRStatus.EXPIRED;
            default -> QRStatus.WAIT;
        };

        if (status == QRStatus.CONFIRMED) {
            String token = json.path("bot_token").asText();
            String url = json.path("baseurl").asText(DEFAULT_BASE);
            String botId = json.path("ilink_bot_id").asText();
            String userId = json.path("ilink_user_id").asText();
            return new QRResult(status, token, url, botId, userId);
        }
        return new QRResult(status, null, null, null, null);
    }

    // ─── Session persistence ────────────────────────────────────────────

    boolean tryLoadSession() {
        var session = WeixinSessionStore.load();
        if (session == null) return false;
        botToken = session.botToken();
        if (session.baseUrl() != null && !session.baseUrl().isBlank()) {
            baseUrl = session.baseUrl();
        }
        ilinkBotId = session.ilinkBotId();
        ilinkUserId = session.ilinkUserId();
        return true;
    }

    void loginFromSession(QRResult result) {
        botToken = result.botToken();
        if (result.baseUrl() != null && !result.baseUrl().isBlank()) {
            baseUrl = result.baseUrl();
        }
        ilinkBotId = result.ilinkBotId();
        ilinkUserId = result.ilinkUserId();
        WeixinSessionStore.save(botToken, baseUrl, ilinkBotId, ilinkUserId);
    }

    boolean isLoggedIn() {
        return botToken != null && !botToken.isBlank();
    }

    // ─── Config ───────────────────────────────────────────────────────

    synchronized void fetchConfig() throws IOException, InterruptedException {
        HttpRequest req = buildRequest("/getconfig")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = JSON.readTree(resp.body());

        if (debug) log.debug("[weixin] /getconfig response [{}]: {}", resp.statusCode(), resp.body());

        String ticket = json.path("typing_ticket").asText(null);
        if (ticket != null && !ticket.isBlank()) {
            typingTicket = ticket;
            typingTicketExpiresAtMs = System.currentTimeMillis()
                + json.path("typing_ticket_expires").asInt(3600) * 1000L;
        }

        int suggestedTimeout = json.path("suggested_timeout").asInt(0);
        if (suggestedTimeout > 0 && suggestedTimeout <= 120) {
            pollTimeoutS = suggestedTimeout;
            log.debug("WeChat poll timeout set to {}s (from server)", suggestedTimeout);
        }
    }

    // ─── Long-polling receive ─────────────────────────────────────────

    int pollTimeoutSeconds() {
        return agentBusy ? AGENT_POLL_TIMEOUT_S : pollTimeoutS;
    }

    boolean isDuplicate(long msgId) {
        if (!seenMsgIds.add(msgId)) {
            log.debug("WeChat duplicate msgId={} ignored", msgId);
            return true;
        }
        if (seenMsgIds.size() > MAX_DEDUP_IDS) {
            var it = seenMsgIds.iterator();
            for (int i = 0; i < 1000 && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
        return false;
    }

    List<WeixinMessage> getUpdates() throws IOException, InterruptedException {
        int timeout = pollTimeoutSeconds();
        String body = String.format(
            "{\"get_updates_buf\":\"%s\",\"base_info\":{\"channel_version\":\"1.0.3\"}}",
            getUpdatesBuf != null ? getUpdatesBuf : "");

        HttpRequest req = buildRequest("/getupdates")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(timeout + 5))
            .build();

        log.debug("WeChat poll start, timeout={}s", timeout);

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("timed out")) {
                log.debug("WeChat poll timeout (normal)");
                return List.of();
            }
            throw e;
        }

        if (debug) {
            String truncated = resp.body().length() > 500
                ? resp.body().substring(0, 500) + "..."
                : resp.body();
            log.debug("[weixin] /getupdates response [{}]: {}", resp.statusCode(), truncated);
        }

        JsonNode json = JSON.readTree(resp.body());
        int ret = json.path("ret").asInt(-1);
        if (ret != 0) {
            int errcode = json.path("errcode").asInt(0);
            // ret=-1 with errcode=0 is a normal long-poll timeout (no messages)
            if (ret == -1 && errcode == 0) {
                return List.of();
            }
            if (errcode == -14) {
                log.warn("WeChat session expired, clearing cached session");
                WeixinSessionStore.clear();
                botToken = null;
                throw new IOException("Session expired — re-authentication needed");
            }
            throw new IOException("Weixin getUpdates error: ret=" + ret
                + " errcode=" + errcode + " " + json.path("errmsg").asText(""));
        }

        int suggested = json.path("suggested_timeout").asInt(0);
        if (suggested > 0 && suggested <= 120) {
            pollTimeoutS = suggested;
        }

        String buf = json.path("get_updates_buf").asText(null);
        if (buf != null && !buf.isBlank()) {
            getUpdatesBuf = buf;
        }

        List<WeixinMessage> messages = new ArrayList<>();
        JsonNode msgs = json.path("msgs");
        if (msgs.isArray()) {
            for (JsonNode msg : msgs) {
                long msgId = msg.path("message_id").asLong();
                String fromUserId = msg.path("from_user_id").asText();
                String ctxToken = msg.path("context_token").asText();
                String text = extractText(msg);
                if (text != null && !text.isBlank()) {
                    messages.add(new WeixinMessage(msgId, fromUserId, text, ctxToken));
                }
            }
        }
        log.debug("WeChat poll received {} messages", messages.size());
        return messages;
    }

    // ─── Send ─────────────────────────────────────────────────────────

    String sendMessage(String toUserId, String text, String contextToken)
        throws IOException, InterruptedException {
        String ctxField = (contextToken != null && !contextToken.isBlank())
            ? "\"context_token\":\"" + contextToken + "\","
            : "";
        String body = String.format(
            "{\"msg\":{\"to_user_id\":\"%s\",\"from_user_id\":\"\","
                + "\"client_id\":\"clawkit-%s\",\"message_type\":2,\"message_state\":2,"
                + "%s"
                + "\"item_list\":[{\"type\":1,\"text_item\":{\"text\":%s}}]},"
                + "\"base_info\":{\"channel_version\":\"2.4.3\"}}",
            toUserId,
            Long.toHexString(ThreadLocalRandom.current().nextLong()),
            ctxField,
            JSON.writeValueAsString(text));

        HttpRequest req = buildRequest("/sendmessage")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        log.debug("WeChat sendMessage to={} len={} → HTTP {} {}",
            toUserId, text.length(), resp.statusCode(),
            debug ? resp.body() : "");

        if (resp.statusCode() != 200) {
            throw new IOException("Weixin sendMessage failed: HTTP " + resp.statusCode()
                + " body=" + resp.body());
        }
        return "";
    }

    String sendMessage(String toUserId, String text) throws IOException, InterruptedException {
        return sendMessage(toUserId, text, null);
    }

    void sendTyping(String toUserId) throws IOException, InterruptedException {
        String ticket = ensureTypingTicket();
        String body = String.format(
            "{\"msg\":{\"to_user_id\":\"%s\",\"from_user_id\":\"\","
                + "\"client_id\":\"clawkit-typ-%s\",\"message_type\":2,\"message_state\":1},"
                + "\"typing_ticket\":\"%s\","
                + "\"base_info\":{\"channel_version\":\"2.4.3\"}}",
            toUserId,
            Long.toHexString(ThreadLocalRandom.current().nextLong()),
            ticket);

        HttpRequest req = buildRequest("/sendtyping")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        http.send(req, HttpResponse.BodyHandlers.discarding());
    }

    private String ensureTypingTicket() throws IOException, InterruptedException {
        if (typingTicket != null && System.currentTimeMillis() < typingTicketExpiresAtMs - 60_000) {
            return typingTicket;
        }
        fetchConfig();
        if (typingTicket == null) {
            throw new IOException("Failed to fetch typing_ticket from getConfig");
        }
        return typingTicket;
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private HttpRequest.Builder buildRequest(String path) throws IOException, InterruptedException {
        String randomUin = Long.toString(ThreadLocalRandom.current().nextInt() & 0xFFFFFFFFL);
        String xWechatUin = Base64.getEncoder().encodeToString(randomUin.getBytes());
        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("AuthorizationType", "ilink_bot_token")
            .header("Authorization", "Bearer " + botToken)
            .header("X-WECHAT-UIN", xWechatUin);
    }

    static String extractText(JsonNode msg) {
        JsonNode items = msg.path("item_list");
        if (!items.isArray()) return null;
        for (JsonNode item : items) {
            if (item.path("type").asInt() == 1) {
                return item.path("text_item").path("text").asText();
            }
        }
        return null;
    }

    // ─── Record ───────────────────────────────────────────────────────

    record WeixinMessage(
        long msgId,
        String fromUserId,
        String text,
        String contextToken
    ) {}
}
