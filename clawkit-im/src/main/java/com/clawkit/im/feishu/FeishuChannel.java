package com.clawkit.im.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.clawkit.engine.AgentStateEvent;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.im.AbstractImChannel;
import com.clawkit.im.ImChannelStatus;
import com.clawkit.im.ImException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

public class FeishuChannel extends AbstractImChannel {

    private final FeishuApi api;
    private final FeishuConfig config;
    private final Set<String> recentEvents = ConcurrentHashMap.newKeySet();

    private HttpServer server;
    private Process ngrokProcess;
    private String publicUrl;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public FeishuChannel(FeishuConfig config) {
        this.config = config;
        this.api = new FeishuApi(config.appId(), config.appSecret());
    }

    // ─── ImChannel identity ──────────────────────────────────────────

    @Override public String id() { return "feishu"; }
    @Override public String name() { return "飞书"; }

    @Override
    public ImChannelStatus status() {
        return new ImChannelStatus("feishu", "飞书", running,
            linkedUserId, publicUrl != null ? publicUrl : "");
    }

    // ─── Lifecycle ────────────────────────────────────────────────────

    @Override
    public void start() throws IOException {
        if (engine == null) {
            throw new IllegalStateException("AgentEngine not set — call setEngine() before start()");
        }

        startNgrok();

        server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/feishu/callback", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] raw = exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");

            String response = handleEvent(new String(raw));
            byte[] respBytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, respBytes.length);
            exchange.getResponseBody().write(respBytes);
            exchange.close();
        });
        server.start();
        running = true;

        printBanner();
    }

    @Override
    public void stop() {
        running = false;
        if (ngrokProcess != null) {
            ngrokProcess.destroy();
            ngrokProcess = null;
        }
        if (server != null) {
            server.stop(2);
        }
        shutdownLatch.countDown();
    }

    @Override
    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    // ─── ngrok ────────────────────────────────────────────────────────

    private void startNgrok() {
        try {
            String existingUrl = fetchNgrokUrl();
            if (existingUrl != null) {
                publicUrl = existingUrl;
                log.info("Reusing existing ngrok tunnel: {}", publicUrl);
                return;
            }
        } catch (Exception ignored) {}

        try {
            ngrokProcess = new ProcessBuilder("ngrok", "http", String.valueOf(config.port()))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();

            for (int i = 0; i < 20; i++) {
                try {
                    Thread.sleep(500);
                    String url = fetchNgrokUrl();
                    if (url != null) {
                        publicUrl = url;
                        log.info("ngrok tunnel established: {}", publicUrl);
                        return;
                    }
                } catch (Exception ignored) {}
            }
            log.warn("ngrok started but could not fetch public URL (is ngrok installed?)");
        } catch (IOException e) {
            log.warn("Could not start ngrok: {}. Run 'ngrok http {}' manually.", e.getMessage(), config.port());
            ngrokProcess = null;
        }
    }

    private static String fetchNgrokUrl() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:4040/api/tunnels"))
            .timeout(Duration.ofSeconds(2))
            .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode tunnels = JSON.readTree(resp.body()).path("tunnels");
        if (tunnels.isArray() && tunnels.size() > 0) {
            return tunnels.get(0).path("public_url").asText();
        }
        return null;
    }

    // ─── Event handling ───────────────────────────────────────────────

    String handleEvent(String body) {
        try {
            JsonNode root = JSON.readTree(body);

            String type = root.path("type").asText();
            if ("url_verification".equals(type)) {
                String challenge = root.path("challenge").asText();
                return "{\"challenge\":\"" + challenge + "\"}";
            }

            JsonNode header = root.path("header");
            String eventType = header.path("event_type").asText();
            String eventId = header.path("event_id").asText();

            if (!eventId.isBlank() && !recentEvents.add(eventId)) {
                return "{\"code\":0}";
            }

            if ("im.message.receive_v1".equals(eventType)) {
                JsonNode event = root.path("event");
                JsonNode message = event.path("message");

                String msgType = message.path("message_type").asText();
                if (!"text".equals(msgType)) return "{\"code\":0}";

                String openId = event.path("sender").path("sender_id").path("open_id").asText();
                String contentStr = message.path("content").asText();
                String text = parseMessageText(contentStr);

                if (text == null || text.isBlank()) return "{\"code\":0}";

                Thread.ofVirtual().start(() -> handleIncomingMessage(openId, text));
            }

            return "{\"code\":0}";

        } catch (Exception e) {
            log.error("Feishu event error: {}", e.getMessage(), e);
            return "{\"code\":0}";
        }
    }

    // ─── AbstractImChannel hooks ──────────────────────────────────────

    @Override
    protected ReplyStream startReply(String userId) throws ImException {
        try {
            String msgId = api.sendMessage(userId, thinkingText());
            return new FeishuReplyStream(msgId, api);
        } catch (IOException | InterruptedException e) {
            throw new ImException("Failed to send placeholder: " + e.getMessage(), e);
        }
    }

    @Override
    protected void sendBusyReply(String userId) throws ImException {
        try {
            api.sendMessage(userId, busyText());
        } catch (IOException | InterruptedException e) {
            throw new ImException("Failed to send busy reply: " + e.getMessage(), e);
        }
    }

    @Override
    protected void sendUserMessage(String userId, String text) throws ImException {
        try {
            for (String chunk : chunkText(simplifyMarkdown(text), 3800)) {
                api.sendMessage(userId, chunk);
            }
        } catch (IOException | InterruptedException e) {
            throw new ImException("Failed to send user message: " + e.getMessage(), e);
        }
    }

    // ─── FeishuReplyStream ────────────────────────────────────────────

    private class FeishuReplyStream implements ReplyStream {

        private final TokenBatcher batcher;
        private final String messageId;
        private final FeishuApi api;

        FeishuReplyStream(String messageId, FeishuApi api) {
            this.messageId = messageId;
            this.api = api;
            this.batcher = new TokenBatcher(messageId, api);
        }

        @Override
        public void onToken(String token) {
            batcher.onToken(token);
        }

        @Override
        public void onStateChange(AgentStateEvent event) {
            String status = mapStateToStatus(event);
            try {
                api.editMessage(messageId, status);
            } catch (Exception ignored) {}
        }

        @Override
        public void finalize(String fullContent) {
            batcher.finalEdit(simplifyMarkdown(fullContent));
        }

        @Override
        public void onError(String errorMessage) {
            try {
                api.editMessage(messageId, errorMessage);
            } catch (Exception ignored) {}
        }
    }

    // ─── TokenBatcher ─────────────────────────────────────────────────

    static class TokenBatcher {
        private static final int MAX_EDITS = 8;
        private static final int FIRST_INTERVAL_MS = 800;
        private static final int FIRST_MIN_CHARS = 50;
        private static final int STEADY_INTERVAL_MS = 1500;
        private static final int STEADY_MIN_CHARS = 100;
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TokenBatcher.class);

        private final String messageId;
        private final FeishuApi api;
        final StringBuilder buffer = new StringBuilder();
        private int editCount;
        private long lastEditTime;
        private int lastFlushedLen;
        private boolean firstEdit = true;

        TokenBatcher(String messageId, FeishuApi api) {
            this.messageId = messageId;
            this.api = api;
            this.lastEditTime = System.currentTimeMillis();
        }

        void onToken(String token) {
            buffer.append(token);
            long now = System.currentTimeMillis();
            int newChars = buffer.length() - lastFlushedLen;

            int minInterval = firstEdit ? FIRST_INTERVAL_MS : STEADY_INTERVAL_MS;
            int minChars = firstEdit ? FIRST_MIN_CHARS : STEADY_MIN_CHARS;

            if (editCount < MAX_EDITS
                && (now - lastEditTime) > minInterval
                && newChars >= minChars) {
                flush();
                lastEditTime = now;
                editCount++;
                firstEdit = false;
            }
        }

        void finalEdit(String fullContent) {
            try {
                api.editMessage(messageId, fullContent);
            } catch (Exception e) {
                log.warn("Final edit failed for {}: {}", messageId, e.getMessage());
            }
        }

        private void flush() {
            if (buffer.isEmpty()) return;
            try {
                api.editMessage(messageId, buffer.toString());
                lastFlushedLen = buffer.length();
            } catch (Exception e) {
                log.debug("Edit flush failed: {}", e.getMessage());
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    static String parseMessageText(String contentStr) {
        if (contentStr == null || contentStr.isBlank()) return null;
        try {
            JsonNode content = JSON.readTree(contentStr);
            return content.path("text").asText();
        } catch (Exception e) {
            return contentStr;
        }
    }

    private void printBanner() {
        String url = publicUrl != null ? publicUrl : config.publicUrl();
        String callbackUrl = (url != null ? url : "http://<your-host>:" + config.port())
            + "/feishu/callback";
        System.out.println();
        System.out.println("  Feishu channel started");
        System.out.println("    port:     " + config.port());
        System.out.println("    callback: " + callbackUrl);
        System.out.println();
    }
}
