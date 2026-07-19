package com.clawkit.tools.mcp;

import com.clawkit.tools.control.ExecutionControl;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP/SSE MCP transport.
 *
 * <p>Every request is sent exactly once. Retrying a JSON-RPC tools/call at the
 * transport layer can duplicate an already committed remote side effect.
 */
public class HttpSseTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpSseTransport.class);
    private static final int CIRCUIT_BREAKER_FAILURES = 3;
    private static final int CIRCUIT_BREAKER_COOLDOWN_SEC = 30;
    private static final Duration MAX_REQUEST_TIME = Duration.ofSeconds(60);

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
    public void start() {
        log.debug("[MCP] HTTP transport ready: {}", url);
    }

    @Override
    public String send(String jsonRpcRequest) throws IOException {
        return send(jsonRpcRequest, ExecutionControl.none());
    }

    @Override
    public String send(String jsonRpcRequest, ExecutionControl control) throws IOException {
        ExecutionControl effective = control != null ? control : ExecutionControl.none();
        effective.checkpoint();
        if (isCircuitOpen()) {
            throw new IOException("[MCP] circuit breaker open for " + url);
        }

        try {
            String result = doSend(jsonRpcRequest, effective);
            consecutiveFailures = 0;
            return result;
        } catch (IOException e) {
            consecutiveFailures++;
            if (consecutiveFailures >= CIRCUIT_BREAKER_FAILURES) {
                circuitOpenUntil = System.currentTimeMillis()
                    + CIRCUIT_BREAKER_COOLDOWN_SEC * 1000L;
                log.warn("[MCP] circuit breaker OPEN for {} ({} consecutive failures)",
                    url, consecutiveFailures);
            }
            throw e;
        }
    }

    private String doSend(String jsonRpcRequest, ExecutionControl control) throws IOException {
        Duration timeout = control.remainingTime()
            .map(remaining -> remaining.compareTo(MAX_REQUEST_TIME) < 0
                ? remaining : MAX_REQUEST_TIME)
            .orElse(MAX_REQUEST_TIME);
        if (timeout.isNegative() || timeout.isZero()) {
            control.checkpoint();
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(jsonRpcRequest))
            .timeout(timeout)
            .build();

        var future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try (var registration = control.onCancel(() -> future.cancel(true))) {
            response = future.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("[MCP] HTTP request interrupted; remote outcome is unknown", e);
        } catch (CancellationException e) {
            throw new IOException("[MCP] HTTP request cancelled; remote outcome is unknown", e);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("[MCP] HTTP request timed out; remote outcome is unknown", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new IOException("[MCP] HTTP request failed: "
                + (cause != null ? cause.getMessage() : e.getMessage()), cause);
        }

        int status = response.statusCode();
        if (status >= 400) {
            throw new IOException("[MCP] HTTP " + status + ": "
                + truncate(response.body(), 200));
        }

        String contentType = response.headers().firstValue("Content-Type")
            .orElse("application/json");
        return contentType.contains("text/event-stream")
            ? parseSse(response.body())
            : response.body();
    }

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
        } catch (IOException ignored) {
            // StringReader does not throw in practice.
        }
        return sb.length() > 0 ? sb.toString() : sseBody;
    }

    private boolean isCircuitOpen() {
        return circuitOpenUntil > 0 && System.currentTimeMillis() < circuitOpenUntil;
    }

    @Override
    public void stop() {
        // No persistent connection.
    }

    @Override
    public boolean isAlive() {
        return !isCircuitOpen();
    }

    private static String truncate(String value, int max) {
        return value != null && value.length() > max
            ? value.substring(0, max) + "..."
            : value;
    }
}
