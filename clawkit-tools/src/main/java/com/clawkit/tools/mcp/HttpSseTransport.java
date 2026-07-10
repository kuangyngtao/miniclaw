package com.clawkit.tools.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** HTTP + SSE MCP 传输。每次 send() 是独立 HTTP POST。支持 5xx 重试 + 熔断器。 */
public class HttpSseTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpSseTransport.class);
    private static final int MAX_RETRIES = 2;
    private static final int CIRCUIT_BREAKER_FAILURES = 3;
    private static final int CIRCUIT_BREAKER_COOLDOWN_SEC = 30;

    private final String url;
    private final HttpClient httpClient;
    private volatile int consecutiveFailures;
    private volatile long circuitOpenUntil;

    public HttpSseTransport(String url) {
        this.url = url;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public void start() throws IOException {
        // HTTP transport 不需要预连接
        log.debug("[MCP] HTTP transport ready: {}", url);
    }

    @Override
    public String send(String jsonRpcRequest) throws IOException {
        if (isCircuitOpen()) {
            throw new IOException("[MCP] circuit breaker open for " + url);
        }

        IOException lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                String result = doSend(jsonRpcRequest);
                consecutiveFailures = 0;
                return result;
            } catch (IOException e) {
                lastError = e;
                log.debug("[MCP] attempt {} failed: {}", attempt + 1, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep((long) Math.pow(2, attempt) * 1000);  // 1s → 2s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("[MCP] interrupted during retry backoff", ie);
                    }
                }
            }
        }

        // 熔断器计数
        consecutiveFailures++;
        if (consecutiveFailures >= CIRCUIT_BREAKER_FAILURES) {
            circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_BREAKER_COOLDOWN_SEC * 1000L;
            log.warn("[MCP] circuit breaker OPEN for {} ({} consecutive failures)", url, consecutiveFailures);
        }
        throw lastError;
    }

    private String doSend(String jsonRpcRequest) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(jsonRpcRequest))
            .timeout(Duration.ofSeconds(60))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("[MCP] HTTP request interrupted", e);
        }

        int status = response.statusCode();
        if (status >= 400) {
            throw new IOException("[MCP] HTTP " + status + ": " + truncate(response.body(), 200));
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("application/json");
        String body = response.body();

        if (contentType.contains("text/event-stream")) {
            return parseSse(body);
        }
        return body;
    }

    /** 解析 SSE 流，提取 data: 行的 JSON */
    private String parseSse(String sseBody) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new StringReader(sseBody))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if (!data.isEmpty()) {
                        sb.append(data);
                    }
                }
            }
        } catch (IOException ignored) {}
        return sb.length() > 0 ? sb.toString() : sseBody;
    }

    private boolean isCircuitOpen() {
        return circuitOpenUntil > 0 && System.currentTimeMillis() < circuitOpenUntil;
    }

    @Override
    public void stop() {
        // 无持久连接需要关闭
    }

    @Override
    public boolean isAlive() {
        return !isCircuitOpen();
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
