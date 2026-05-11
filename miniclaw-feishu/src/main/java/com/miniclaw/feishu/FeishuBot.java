package com.miniclaw.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.engine.PermissionMode;
import com.miniclaw.engine.impl.AgentEngine;
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
import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feishu companion — embedded HTTP server that receives Feishu callbacks
 * and routes them to a shared AgentEngine.
 *
 * Single-user: one openId → one engine. Start with /feishu-on, stop with /feishu-off.
 */
public class FeishuBot {

    private static final Logger log = LoggerFactory.getLogger(FeishuBot.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FeishuApi api;
    private final FeishuConfig config;
    private final Set<String> recentEvents = ConcurrentHashMap.newKeySet();

    private AgentEngine engine;
    private volatile String linkedOpenId;
    private volatile Consumer<String> onFeishuInput;
    private TokenBatcher cliBatcher;
    private Consumer<String> cliListener;
    private String cliMsgId;
    private HttpServer server;
    private Process ngrokProcess;
    private String publicUrl;
    private volatile boolean running;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public FeishuBot(FeishuConfig config) {
        this.config = config;
        this.api = new FeishuApi(config.appId(), config.appSecret());
    }

    /** Inject the shared AgentEngine (from CLI). */
    public void setEngine(AgentEngine engine) {
        this.engine = engine;
    }

    public boolean isRunning() {
        return running;
    }

    // ─── Start / Stop ────────────────────────────────────────────────

    public void start() throws IOException {
        if (engine == null) {
            throw new IllegalStateException("AgentEngine not set — call setEngine() before start()");
        }

        // Start ngrok tunnel
        startNgrok();

        // Start HTTP server
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
        log.info("Feishu companion started on port {}", config.port());
    }

    private void startNgrok() {
        // Check if ngrok is already running
        try {
            String existingUrl = fetchNgrokUrl();
            if (existingUrl != null) {
                publicUrl = existingUrl;
                log.info("Reusing existing ngrok tunnel: {}", publicUrl);
                return;
            }
        } catch (Exception ignored) {}

        // Start ngrok as a child process
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
        log.info("Feishu companion stopped");
    }

    /** Block until the server shuts down. For headless --feishu mode. */
    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    // ─── Event Handling ──────────────────────────────────────────────

    String handleEvent(String body) {
        try {
            JsonNode root = JSON.readTree(body);

            String type = root.path("type").asText();
            if ("url_verification".equals(type)) {
                String challenge = root.path("challenge").asText();
                log.info("Feishu URL verification challenge received");
                return "{\"challenge\":\"" + challenge + "\"}";
            }

            JsonNode header = root.path("header");
            String eventType = header.path("event_type").asText();
            String eventId = header.path("event_id").asText();

            if (!eventId.isBlank() && !recentEvents.add(eventId)) {
                log.debug("Duplicate event ignored: {}", eventId);
                return "{\"code\":0}";
            }

            log.info("Feishu event received: type={}, event_id={}", eventType, eventId);

            if ("im.message.receive_v1".equals(eventType)) {
                JsonNode event = root.path("event");
                JsonNode message = event.path("message");

                String msgType = message.path("message_type").asText();
                if (!"text".equals(msgType)) {
                    log.info("Feishu non-text message ignored: type={}", msgType);
                    return "{\"code\":0}";
                }

                String openId = event.path("sender").path("sender_id").path("open_id").asText();
                String contentStr = message.path("content").asText();
                String text = parseMessageText(contentStr);

                if (text == null || text.isBlank()) {
                    return "{\"code\":0}";
                }

                log.info("Message from {}: {}", openId, text.substring(0, Math.min(80, text.length())));

                // Process async to return 200 quickly
                Thread.ofVirtual().start(() -> handleMessage(openId, text));
            }

            return "{\"code\":0}";

        } catch (Exception e) {
            log.error("Feishu event error: {}", e.getMessage(), e);
            return "{\"code\":0}";
        }
    }

    // ─── Message Handling ────────────────────────────────────────────

    private void handleMessage(String openId, String text) {
        linkedOpenId = openId;
        if (onFeishuInput != null) onFeishuInput.accept(text);

        if (!engine.tryAcquire()) {
            try {
                api.sendMessage(openId, "正在处理中，请稍候...");
            } catch (Exception e) {
                log.warn("Failed to send busy reply: {}", e.getMessage());
            }
            return;
        }

        try {
            String msgId;
            try {
                msgId = api.sendMessage(openId, "▋ thinking...");
            } catch (Exception e) {
                log.error("Failed to send placeholder: {}", e.getMessage());
                try {
                    api.sendMessage(openId, "[A-002] " + e.getMessage());
                } catch (Exception ignored) {}
                return;
            }

            TokenBatcher batcher = new TokenBatcher(msgId, api);
            Consumer<String> listener = batcher::onToken;
            engine.addOnTokenListener(listener);

            PermissionMode savedMode = engine.permissionMode();
            engine.setPermissionMode(PermissionMode.AUTO);

            String result;
            try {
                result = engine.run(text);
            } catch (Exception e) {
                log.error("Engine error: {}", e.getMessage());
                api.editMessage(msgId, "[A-002] " + e.getMessage());
                return;
            } finally {
                engine.setPermissionMode(savedMode);
                engine.removeOnTokenListener(listener);
            }

            String finalContent = result != null && !result.isBlank()
                ? result
                : batcher.buffer.toString();
            if (finalContent.isBlank()) {
                finalContent = "No output.";
            }
            batcher.finalEdit(finalContent);

        } catch (Exception e) {
            log.error("Message handling error: {}", e.getMessage(), e);
        } finally {
            engine.release();
        }
    }

    // ─── CLI Mirror ──────────────────────────────────────────────────

    /** Register a callback for Feishu→terminal input notification. */
    public void setOnFeishuInput(Consumer<String> callback) {
        this.onFeishuInput = callback;
    }

    /**
     * Called before CLI engine.run(). Sends the user's input as a Feishu message,
     * then sends a placeholder that will be stream-edited with the LLM response.
     */
    public void mirrorToFeishu(String userInput) {
        if (linkedOpenId == null) return;
        try {
            api.sendMessage(linkedOpenId, "👤 " + userInput);
            cliMsgId = api.sendMessage(linkedOpenId, "▋ thinking...");
            cliBatcher = new TokenBatcher(cliMsgId, api);
            cliListener = cliBatcher::onToken;
            engine.addOnTokenListener(cliListener);
        } catch (Exception e) {
            log.warn("Failed to start Feishu mirror: {}", e.getMessage());
            cliMsgId = null;
            cliBatcher = null;
            cliListener = null;
        }
    }

    /** Called after CLI engine.run(). Finalizes the Feishu mirror message. */
    public void finalizeMirror(String result) {
        if (cliMsgId == null || cliBatcher == null) return;
        try {
            engine.removeOnTokenListener(cliListener);
            String content = (result != null && !result.isBlank())
                ? result : cliBatcher.buffer.toString();
            if (content.isBlank()) content = "No output.";
            cliBatcher.finalEdit(content);
        } catch (Exception e) {
            log.warn("Failed to finalize Feishu mirror: {}", e.getMessage());
        } finally {
            cliBatcher = null;
            cliMsgId = null;
            cliListener = null;
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────

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
        System.out.println("  ╔════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("  ║  Feishu companion  |  /feishu-on           ║");
        System.out.println("  ║  HTTP :" + padRight(String.valueOf(config.port()), 38) + "║");
        System.out.println("  ║  Callback " + padRight(callbackUrl, 36) + "║");
        System.out.println("  ╚════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════");
        System.out.println();
    }

    private static String padRight(String s, int n) {
        if (s == null || s.length() >= n) return s;
        return s + " ".repeat(n - s.length());
    }

    // ─── TokenBatcher ────────────────────────────────────────────────

    /**
     * Two-phase batching: fast first response (50 chars / 0.8s), then
     * steady updates (100 chars / 1.5s). Feishu caps at ~10 edits/msg.
     */
    static class TokenBatcher {
        private static final int MAX_EDITS = 8;
        private static final int FIRST_INTERVAL_MS = 800;
        private static final int FIRST_MIN_CHARS = 50;
        private static final int STEADY_INTERVAL_MS = 1500;
        private static final int STEADY_MIN_CHARS = 100;

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
}
