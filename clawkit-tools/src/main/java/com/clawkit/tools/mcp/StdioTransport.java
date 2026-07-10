package com.clawkit.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** stdio 子进程 MCP 传输。启动子进程，通过 stdin/stdout 交换 JSON-RPC 消息。 */
public class StdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SHUTDOWN_CLOSE_STDIN_WAIT_MS = 1000;
    private static final int SHUTDOWN_TERM_WAIT_MS = 2000;
    private static final int STDER_RING_SIZE = 1000;

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
        pb.environment().putAll(env);
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
                        log.debug("[MCP] stdout non-JSON: {}", line.substring(0, Math.min(line.length(), 80)));
                    }
                }
            } catch (IOException e) {
                log.debug("[MCP] stdout reader exited: {}", e.getMessage());
            }
            // EOF: 进程意外退出，fail 所有 pending
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
                    log.info("[MCP:{}] {}", command, line);
                }
            } catch (IOException e) {
                log.debug("[MCP] stderr reader exited: {}", e.getMessage());
            }
        }, "mcp-stderr-" + command);
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Override
    public String send(String jsonRpcRequest) throws IOException {
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
            // 通知类消息，无需等待响应
            writeLine(jsonRpcRequest);
            return "{}";
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        synchronized (this) {
            writeLine(jsonRpcRequest);
        }

        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            pendingRequests.remove(id);
            throw new IOException("[MCP] request timed out (60s): " + command + " id=" + id);
        } catch (InterruptedException e) {
            pendingRequests.remove(id);
            Thread.currentThread().interrupt();
            throw new IOException("[MCP] request interrupted: " + command);
        } catch (java.util.concurrent.ExecutionException e) {
            pendingRequests.remove(id);
            Throwable cause = e.getCause();
            throw new IOException("[MCP] request failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
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

    public List<String> getStderrLog() {
        return List.copyOf(stderrRing);
    }

    /** 固定大小的环形缓冲区 */
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
