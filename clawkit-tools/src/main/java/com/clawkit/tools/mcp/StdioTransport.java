package com.clawkit.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawkit.tools.control.ExecutionControl;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * stdio subprocess MCP transport. Starts a child process, exchanges
 * JSON-RPC messages over stdin/stdout.
 *
 * <h3>stderr handling (PRODUCT-1 §10.2)</h3>
 * <ul>
 *   <li>stderr is sanitized before logging — no raw SSH error messages</li>
 *   <li>Each line is logged at DEBUG (not INFO) after sanitization</li>
 *   <li>External consumers get bounded, sanitized diagnostics only</li>
 *   <li>Raw stderr exists only within the process lifetime for error classification</li>
 *   <li>Never written to disk, model context, RunEvent, Session, or Memory</li>
 * </ul>
 */
public class StdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SHUTDOWN_CLOSE_STDIN_WAIT_MS = 1000;
    private static final int SHUTDOWN_TERM_WAIT_MS = 2000;
    private static final int STDER_RING_SIZE = 1000;
    private static final int MAX_DIAGNOSTIC_CHARS = 500;

    // Patterns for sanitizing SSH stderr — remove paths, IPs, host references
    // Order matters: key/cert paths first, then user directories, then IPs/hosts
    private static final Pattern KEY_CERT_PATTERN = Pattern.compile(
        "[/\\\\]\\.ssh[/\\\\][^\\s:\"']+"
            + "|\\S+\\.(?:pem|key|ppk|cer|der|p12|pfx)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern USER_DIR_PATTERN = Pattern.compile(
        "(?:/home/|/Users/|C:\\\\Users\\\\)[^\\s:\"\\\\/]+",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_KEY_PATTERN = Pattern.compile(
        "(^|[\\s\"'(])id_[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)?",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SSH_AUTH_SOCK_PATTERN = Pattern.compile(
        "(?:SSH_AUTH_SOCK|ssh-auth-sock|agent\\.\\d+)[^\\s]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern IP_PATTERN = Pattern.compile(
        "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern USER_AT_HOST_PATTERN = Pattern.compile(
        "[\\w.-]+@[\\w.-]+");

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final Path workDir;
    private final ConcurrentHashMap<Long, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private Process process;
    private BufferedWriter stdin;
    private Thread stdoutThread;
    private Thread stderrThread;
    private final List<String> stderrRing = Collections.synchronizedList(new RingBuffer<>(STDER_RING_SIZE));
    private final List<String> sanitizedDiagnostics = Collections.synchronizedList(new ArrayList<>());

    public StdioTransport(String command, List<String> args, Map<String, String> env, Path workDir) {
        this.command = command;
        this.args = args;
        this.env = env != null ? env : Map.of();
        this.workDir = workDir;
    }

    @Override
    public void start() throws IOException {
        if (!started.compareAndSet(false, true)) return;

        ProcessBuilder pb = new ProcessBuilder(command);
        if (args != null && !args.isEmpty()) {
            pb.command().addAll(args);
        }
        // Use explicit env whitelist instead of inheriting parent process
        if (!env.isEmpty()) {
            Map<String, String> processEnv = pb.environment();
            processEnv.clear();
            processEnv.putAll(env);
        }
        pb.redirectErrorStream(false);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        try {
            process = pb.start();
        } catch (IOException e) {
            started.set(false);
            throw new IOException("[MCP] failed to start child process: " + command, e);
        }

        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        stdoutThread = startStdoutReader();
        stderrThread = startStderrReader();

        log.debug("[MCP] stdio transport started: {}", command);
    }

    private Thread startStdoutReader() {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JsonNode node = MAPPER.readTree(line);
                        long id = node.get("id").asLong(-1);
                        if (id >= 0) {
                            CompletableFuture<String> future = pendingRequests.remove(id);
                            if (future != null) {
                                future.complete(line);
                            }
                        }
                    } catch (Exception e) {
                        log.debug("[MCP] stdout non-JSON: {}",
                            line.substring(0, Math.min(line.length(), 80)));
                    }
                }
            } catch (IOException e) {
                log.debug("[MCP] stdout reader exited: {}", e.getMessage());
            }
            // EOF: process exited unexpectedly, fail all pending
            failAllPending("MCP server exited");
        }, "mcp-stdout-" + command);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private Thread startStderrReader() {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderrRing.add(line);
                    String sanitized = sanitizeDiagnostic(line);
                    synchronized (sanitizedDiagnostics) {
                        sanitizedDiagnostics.add(sanitized);
                    }
                    // Log sanitized output at DEBUG only — normal connections
                    // should not flood INFO with SSH diagnostics
                    log.debug("[MCP:{}] {}", command, sanitized);
                }
            } catch (IOException e) {
                log.debug("[MCP] stderr reader exited: {}", e.getMessage());
            }
        }, "mcp-stderr-" + command);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Sanitize a diagnostic line by removing sensitive patterns.
     *
     * <p>Removes: user home directories, key/certificate paths,
     * SSH_AUTH_SOCK references, IP addresses, username@hostname patterns.
     * Returns a bounded summary.
     */
    static String sanitizeDiagnostic(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String s = raw;
        s = KEY_CERT_PATTERN.matcher(s).replaceAll("[key-path]");
        s = ID_KEY_PATTERN.matcher(s).replaceAll("$1[id-key]");
        s = USER_DIR_PATTERN.matcher(s).replaceAll("[user-dir]");
        s = SSH_AUTH_SOCK_PATTERN.matcher(s).replaceAll("[agent-socket]");
        s = IP_PATTERN.matcher(s).replaceAll("[ip]");
        s = USER_AT_HOST_PATTERN.matcher(s).replaceAll("[user@host]");
        if (s.length() > MAX_DIAGNOSTIC_CHARS) {
            s = s.substring(0, MAX_DIAGNOSTIC_CHARS) + "…";
        }
        return s;
    }

    @Override
    public String send(String jsonRpcRequest) throws IOException {
        return send(jsonRpcRequest, ExecutionControl.none());
    }

    @Override
    public String send(String jsonRpcRequest, ExecutionControl control) throws IOException {
        ExecutionControl effective = control != null ? control : ExecutionControl.none();
        effective.checkpoint();
        if (!isAlive()) {
            throw new IOException("[MCP] transport not alive: " + command);
        }

        long id;
        try {
            JsonNode idNode = MAPPER.readTree(jsonRpcRequest).get("id");
            id = idNode != null ? idNode.asLong(-1) : -1;
        } catch (Exception e) {
            throw new IOException("[MCP] failed to parse request: " + e.getMessage(), e);
        }
        if (id < 0) {
            // Notification — no response expected
            writeLine(jsonRpcRequest);
            return "{}";
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        synchronized (this) {
            writeLine(jsonRpcRequest);
        }

        long timeoutMillis = effective.remainingTime()
            .map(d -> Math.min(60_000L, Math.max(1L, d.toMillis())))
            .orElse(60_000L);
        try (var registration = effective.onCancel(() ->
                future.completeExceptionally(new IOException(
                    "[MCP] request cancelled; remote outcome is unknown")))) {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            pendingRequests.remove(id);
            throw new IOException("[MCP] request timed out; remote outcome is unknown: "
                + command + " id=" + id);
        } catch (InterruptedException e) {
            pendingRequests.remove(id);
            Thread.currentThread().interrupt();
            throw new IOException("[MCP] request interrupted: " + command);
        } catch (java.util.concurrent.ExecutionException e) {
            pendingRequests.remove(id);
            Throwable cause = e.getCause();
            throw new IOException("[MCP] request failed: "
                + (cause != null ? cause.getMessage() : e.getMessage()));
        }
    }

    private void writeLine(String line) throws IOException {
        stdin.write(line);
        stdin.newLine();
        stdin.flush();
    }

    @Override
    public void stop() {
        started.set(false);

        // Step 1: close stdin, give server chance to exit gracefully
        try {
            if (stdin != null) stdin.close();
        } catch (IOException ignored) {}
        waitForProcess(SHUTDOWN_CLOSE_STDIN_WAIT_MS);

        // Step 2: SIGTERM (destroy)
        if (process != null && process.isAlive()) {
            process.destroy();
            waitForProcess(SHUTDOWN_TERM_WAIT_MS);
        }

        // Step 3: SIGKILL (destroyForcibly)
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            try { process.waitFor(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }

        failAllPending("MCP server stopped");

        // Clear all stderr buffers — raw stderr must not survive process lifetime
        stderrRing.clear();
        synchronized (sanitizedDiagnostics) {
            sanitizedDiagnostics.clear();
        }

        log.debug("[MCP] stdio transport stopped: {}", command);
    }

    private void waitForProcess(int ms) {
        if (process == null) return;
        try {
            process.waitFor(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void failAllPending(String reason) {
        String msg = "[MCP] " + reason + " with exit code "
            + (process != null && !process.isAlive() ? process.exitValue() : "unknown");
        for (var entry : pendingRequests.entrySet()) {
            entry.getValue().completeExceptionally(new IOException(msg));
        }
        pendingRequests.clear();
    }

    @Override
    public boolean isAlive() {
        return started.get() && process != null && process.isAlive();
    }

    /**
     * Returns SANITIZED, bounded diagnostics safe for external consumers.
     * Each line has sensitive patterns (paths, IPs, user@host) removed.
     * This is the ONLY public stderr accessor.
     *
     * <p>Raw stderr exists only for internal error classification and is
     * cleared on stop/close.
     */
    public List<String> getStderrLog() {
        synchronized (sanitizedDiagnostics) {
            return List.copyOf(sanitizedDiagnostics);
        }
    }

    /**
     * Return bounded, sanitized diagnostic text for structured connection
     * classification. No raw stderr leaves this transport.
     */
    public String sanitizedDiagnosticSummary() {
        synchronized (sanitizedDiagnostics) {
            int from = Math.max(0, sanitizedDiagnostics.size() - 8);
            return String.join("\n", sanitizedDiagnostics.subList(from, sanitizedDiagnostics.size()));
        }
    }

    /**
     * Package-private: get sanitized last N lines for error classification.
     * Used by {@code RemoteMcpSession} to categorize SSH failures without
     * exposing raw stderr.
     */
    List<String> internalDiagnosticTail(int maxLines) {
        synchronized (sanitizedDiagnostics) {
            int size = sanitizedDiagnostics.size();
            int start = Math.max(0, size - maxLines);
            return List.copyOf(sanitizedDiagnostics.subList(start, size));
        }
    }

    /** Fixed-size ring buffer. */
    private static class RingBuffer<T> extends ArrayList<T> {
        private final int maxSize;
        RingBuffer(int maxSize) { this.maxSize = maxSize; }
        @Override
        public boolean add(T item) {
            if (size() >= maxSize) evictFirst();
            return super.add(item);
        }
        private void evictFirst() { remove(0); }
    }
}
