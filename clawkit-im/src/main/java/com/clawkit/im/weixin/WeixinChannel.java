package com.clawkit.im.weixin;

import com.clawkit.engine.AgentStateEvent;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.im.AbstractImChannel;
import com.clawkit.im.ImChannelStatus;
import com.clawkit.im.ImException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

public class WeixinChannel extends AbstractImChannel {

    private static final int MAX_CHUNK_LEN = 3800;
    private static final int FLUSH_INTERVAL_MS = 1200;
    private static final int FLUSH_MIN_CHARS = 180;
    private static final int TYPING_INTERVAL_MS = 5000;
    private static final int QR_POLL_INTERVAL_MS = 2000;
    private static final int QR_POLL_TIMEOUT_MS = 120_000;

    private final WeixinIlinkClient client;
    private final WeixinConfig config;
    private final BlockingQueue<PendingMsg> msgQueue = new LinkedBlockingQueue<>();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private volatile Thread pollThread;
    private volatile Thread consumerThread;
    private volatile String boundUserId;
    private volatile String currentContextToken;
    private volatile int queueDepth;

    public WeixinChannel(WeixinConfig config) {
        this.config = config;
        this.client = new WeixinIlinkClient(config.debug());
    }

    // ─── ImChannel identity ──────────────────────────────────────────

    @Override public String id() { return "weixin"; }
    @Override public String name() { return "微信"; }

    @Override
    public ImChannelStatus status() {
        return new ImChannelStatus("weixin", "微信", running,
            linkedUserId,
            pollThread != null ? "polling q=" + queueDepth : "stopped");
    }

    // ─── Lifecycle ────────────────────────────────────────────────────

    @Override
    public void start() throws IOException {
        if (engine == null) {
            throw new IllegalStateException("AgentEngine not set — call setEngine() before start()");
        }

        // Try to restore persisted session
        if (client.tryLoadSession()) {
            System.out.println("  WeChat: restored session, user=" + client.boundUserId());
        } else {
            // QR code login flow
            System.out.println("  WeChat: getting QR code...");
            WeixinIlinkClient.QRCode qr;
            try {
                qr = client.getQRCode();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }

            printQRCode(qr.qrcodeUrl());

            // Poll for scan
            long start = System.currentTimeMillis();
            WeixinIlinkClient.QRStatus lastStatus = null;
            while (System.currentTimeMillis() - start < QR_POLL_TIMEOUT_MS) {
                try {
                    Thread.sleep(QR_POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Login cancelled");
                }

                WeixinIlinkClient.QRResult result;
                try {
                    result = client.pollQRCodeStatus(qr.qrcode());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Login cancelled", e);
                }

                if (result.status() != lastStatus) {
                    lastStatus = result.status();
                    switch (result.status()) {
                        case SCANNED -> System.out.println("  WeChat: QR code scanned, waiting for confirmation...");
                        case EXPIRED -> throw new IOException("QR code expired. Restart to get a new one.");
                        case CONFIRMED -> {
                            System.out.println("  WeChat: login confirmed!");
                            client.loginFromSession(result);
                            break;
                        }
                    }
                }

                if (result.status() == WeixinIlinkClient.QRStatus.CONFIRMED) break;
            }

            if (!client.isLoggedIn()) {
                throw new IOException("QR login timed out after " + QR_POLL_TIMEOUT_MS / 1000 + "s");
            }
        }

        boundUserId = client.boundUserId();

        // Fetch config for typing ticket
        try {
            client.fetchConfig();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.warn("WeChat getConfig failed (typing may not work): {}", e.getMessage());
        }

        running = true;
        pollThread = Thread.ofVirtual().start(this::pollLoop);
        consumerThread = Thread.ofVirtual().start(this::consumerLoop);
        System.out.println("  WeChat channel started.");
    }

    @Override
    public void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
        if (consumerThread != null) {
            consumerThread.interrupt();
            consumerThread = null;
        }
        shutdownLatch.countDown();
    }

    @Override
    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    // ─── QR display ───────────────────────────────────────────────────

    private void printQRCode(String url) {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════╗");
        System.out.println("  ║         扫码登录 ClawBot                             ║");
        System.out.println("  ╠══════════════════════════════════════════════════════╣");

        // Try qrencode first for real terminal QR
        if (!tryQrencode(url)) {
            // Fallback: show URL
            System.out.println("  ║  用浏览器打开此链接:                                  ║");
            System.out.println("  ║  " + padRight(url, 51) + " ║");
            if (url.length() > 51) {
                String rest = url.substring(51);
                for (int i = 0; i < rest.length(); i += 51) {
                    int end = Math.min(i + 51, rest.length());
                    System.out.println("  ║  " + padRight(rest.substring(i, end), 51) + " ║");
                }
            }
            System.out.println("  ╠══════════════════════════════════════════════════════╣");
            System.out.println("  ║  Tip: install qrencode for in-terminal QR code      ║");
            System.out.println("  ║  brew install qrencode / apt install qrencode       ║");
        }

        System.out.println("  ╠══════════════════════════════════════════════════════╣");
        System.out.println("  ║  等待 " + (QR_POLL_TIMEOUT_MS / 1000) + " 秒                        ║");
        System.out.println("  ╚══════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private boolean tryQrencode(String text) {
        try {
            Process p = new ProcessBuilder("qrencode", "-t", "ANSIUTF8", "-o", "-", text)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            String output = new String(p.getInputStream().readAllBytes());
            int rc = p.waitFor();
            if (rc == 0 && !output.isBlank()) {
                System.out.println("  ║" + padRight("", 52) + "║");
                for (String line : output.split("\n")) {
                    // qrencode ANSIUTF8 uses half-block chars — pad to align
                    String clean = line.replace("\r", "");
                    System.out.println("  " + clean);
                }
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String padRight(String s, int n) {
        if (s.length() >= n) return s;
        return s + " ".repeat(n - s.length());
    }

    // ─── Poll loop ────────────────────────────────────────────────────

    private void pollLoop() {
        System.out.println("[weixin] poll loop started, baseUrl=" + client.baseUrl());
        log.info("WeChat poll loop started");
        while (running) {
            try {
                var messages = client.getUpdates();
                if (!messages.isEmpty()) {
                    System.out.println("[weixin] poll returned " + messages.size() + " message(s)");
                }
                for (var msg : messages) {
                    if (msg.text() == null || msg.text().isBlank()) continue;
                    if (client.isDuplicate(msg.msgId())) continue;

                    System.out.println("[weixin] recv: userId=" + msg.fromUserId()
                        + " msgId=" + msg.msgId()
                        + " text=" + (msg.text().length() > 80 ? msg.text().substring(0, 80) + "..." : msg.text()));

                    if (boundUserId == null || boundUserId.isBlank()) {
                        boundUserId = msg.fromUserId();
                        System.out.println("[weixin] bound to user: " + boundUserId);
                    } else if (!boundUserId.equals(msg.fromUserId())) {
                        System.out.println("[weixin] ignored non-bound user: " + msg.fromUserId());
                        continue;
                    }

                    String text = msg.text().trim();
                    if (text.startsWith("/")) {
                        System.out.println("[weixin] command: " + text);
                        handleCommand(msg.fromUserId(), text);
                        continue;
                    }

                    msgQueue.put(new PendingMsg(msg.fromUserId(), msg.text(), msg.contextToken()));
                    queueDepth = msgQueue.size();
                    System.out.println("[weixin] enqueued, queueDepth=" + queueDepth);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                if (running) {
                    System.err.println("[weixin] poll error: " + e.getMessage());
                    log.warn("WeChat poll error: {}", e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.info("WeChat poll loop stopped");
    }

    // ─── Consumer loop ────────────────────────────────────────────────

    private void consumerLoop() {
        System.out.println("[weixin] consumer loop started");
        log.info("WeChat consumer loop started");
        while (running) {
            try {
                PendingMsg msg = msgQueue.take();
                System.out.println("[weixin] consumer dequeued: userId=" + msg.userId()
                    + " text=" + (msg.text().length() > 80 ? msg.text().substring(0, 80) + "..." : msg.text()));
                queueDepth = msgQueue.size();
                linkedUserId = msg.userId;
                currentContextToken = msg.contextToken;
                client.setAgentBusy(true);
                handleIncomingMessage(msg.userId, msg.text);
                System.out.println("[weixin] consumer processed, reply sent");
                currentContextToken = null;
                client.setAgentBusy(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[weixin] consumer error: " + e.getMessage());
                log.error("WeChat consumer error: {}", e.getMessage(), e);
            }
        }
        log.info("WeChat consumer loop stopped");
    }

    // ─── Commands ──────────────────────────────────────────────────────

    private void handleCommand(String userId, String text) {
        String cmd = text.trim().toLowerCase();
        log.info("WeChat command: {} from {}", cmd, userId);
        try {
            switch (cmd) {
                case "/help" -> client.sendMessage(userId,
                    "PaiCLI 微信通道\n"
                    + "/help    查看帮助\n"
                    + "/status  通道状态\n"
                    + "/clear   清空会话\n"
                    + "/compact 压缩上下文\n"
                    + "/stop    停止当前任务");
                case "/status" -> client.sendMessage(userId,
                    "通道: 微信 (ClawBot)\n"
                    + "状态: " + (running ? "运行中" : "已停止") + "\n"
                    + "队列: " + queueDepth + " 条待处理\n"
                    + "绑定用户: " + (boundUserId != null ? boundUserId : "无"));
                case "/clear" -> {
                    if (engine != null) engine.clearSession();
                    client.sendMessage(userId, "会话已清空。");
                }
                case "/compact" -> {
                    if (engine != null) engine.compactSession();
                    client.sendMessage(userId, "上下文已压缩。");
                }
                case "/stop" -> {
                    if (engine != null) engine.interrupt();
                    msgQueue.clear();
                    queueDepth = 0;
                    client.sendMessage(userId, "任务已停止，队列已清空。");
                }
                default -> client.sendMessage(userId,
                    "未知命令: " + cmd + "。发送 /help 查看可用命令。");
            }
        } catch (Exception e) {
            log.warn("WeChat command error: {}", e.getMessage());
        }
    }

    // ─── AbstractImChannel hooks ──────────────────────────────────────

    @Override
    protected ReplyStream startReply(String userId) throws ImException {
        try {
            client.sendTyping(userId);
            return new WeixinReplyStream(userId, currentContextToken, client);
        } catch (IOException | InterruptedException e) {
            throw new ImException("Failed to start WeChat reply: " + e.getMessage(), e);
        }
    }

    @Override
    protected void sendBusyReply(String userId) throws ImException {
        try {
            client.sendMessage(userId, busyText());
        } catch (IOException | InterruptedException e) {
            throw new ImException("Failed to send busy reply: " + e.getMessage(), e);
        }
    }

    @Override
    protected void sendUserMessage(String userId, String text) throws ImException {
        try {
            for (String chunk : chunkText(simplifyMarkdown(text), MAX_CHUNK_LEN)) {
                client.sendMessage(userId, chunk);
            }
        } catch (IOException | InterruptedException e) {
            throw new ImException("Failed to send user message: " + e.getMessage(), e);
        }
    }

    // ─── WeixinReplyStream ────────────────────────────────────────────

    private static class WeixinReplyStream implements ReplyStream {
        private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WeixinReplyStream.class);

        private final String userId;
        private final WeixinIlinkClient client;
        private final String contextToken;
        private final StringBuilder buffer = new StringBuilder();
        private long lastTypingTime;
        private long lastFlushTime;
        private int lastFlushedLen;

        WeixinReplyStream(String userId, String contextToken, WeixinIlinkClient client) {
            this.userId = userId;
            this.contextToken = contextToken;
            this.client = client;
            long now = System.currentTimeMillis();
            this.lastTypingTime = now;
            this.lastFlushTime = now;
        }

        @Override
        public void onToken(String token) {
            buffer.append(token);
            long now = System.currentTimeMillis();
            int newChars = buffer.length() - lastFlushedLen;

            if (now - lastTypingTime > TYPING_INTERVAL_MS) {
                try {
                    client.sendTyping(userId);
                    lastTypingTime = now;
                } catch (Exception ignored) {}
            }

            if (now - lastFlushTime > FLUSH_INTERVAL_MS
                && newChars >= FLUSH_MIN_CHARS) {
                try {
                    String text = simplifyMarkdown(buffer.toString());
                    for (String chunk : chunkText(text, MAX_CHUNK_LEN)) {
                        client.sendMessage(userId, chunk, contextToken);
                    }
                    lastFlushedLen = buffer.length();
                    lastFlushTime = now;
                } catch (Exception ignored) {}
            }
        }

        @Override
        public void onStateChange(AgentStateEvent event) {
            // WeChat has no edit-message API — quiet
        }

        @Override
        public void finalize(String fullContent) {
            try {
                String simplified = simplifyMarkdown(fullContent);
                List<String> chunks = chunkText(simplified, MAX_CHUNK_LEN);
                for (String chunk : chunks) {
                    client.sendMessage(userId, chunk, contextToken);
                }
                log.debug("WeChat final reply: {} chars → {} chunks",
                    fullContent.length(), chunks.size());
            } catch (Exception e) {
                log.warn("Failed to send final reply: {}", e.getMessage());
            }
        }

        @Override
        public void onError(String errorMessage) {
            try {
                client.sendMessage(userId, "[X-001] " + errorMessage, contextToken);
            } catch (Exception e) {
                log.warn("Failed to send error via WeChat: {}", e.getMessage());
            }
        }
    }

    // ─── Pending message ──────────────────────────────────────────────

    private record PendingMsg(String userId, String text, String contextToken) {}
}
