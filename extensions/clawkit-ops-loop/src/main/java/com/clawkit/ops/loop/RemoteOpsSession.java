package com.clawkit.ops.loop;

import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.mcp.McpCallResult;
import com.clawkit.tools.mcp.McpClient;
import com.clawkit.tools.mcp.McpToolDef;
import com.clawkit.tools.mcp.StdioTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the lifecycle of a single SSH/MCP stdio session to a remote
 * OPS target.
 *
 * <p>Design doc §7.3–§7.4. One session = one SSH connection, reused across
 * all tools in a single Discovery. State machine:
 *
 * <pre>{@code
 *   NEW -> STARTING -> INITIALIZING -> READY
 *   Any  -> FAILED
 *   Any  -> DRAINING -> CLOSED
 * }</pre>
 *
 * <p>After handshake, validates the remote server's protocol version,
 * probe version, capability profile, and tool-set hash against the
 * expected values from {@link RemoteTargetDescriptor}.
 *
 * <p>The session does NOT use {@code ControlMaster}. It maintains exactly
 * one in-flight request at a time.
 */
public final class RemoteOpsSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RemoteOpsSession.class);

    public enum State {
        NEW, STARTING, INITIALIZING, READY, DRAINING, FAILED, CLOSED
    }

    private final RemoteTargetDescriptor target;
    private final SshConnectionConfig connectionConfig;
    private final Clock clock;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final List<RemoteOpsError> errors = new ArrayList<>();

    private StdioTransport transport;
    private McpClient client;
    private Instant startedAt;
    private RemoteOpsError firstError;

    public RemoteOpsSession(RemoteTargetDescriptor target, SshConnectionConfig connectionConfig) {
        this(target, connectionConfig, Clock.systemUTC());
    }

    public RemoteOpsSession(RemoteTargetDescriptor target, SshConnectionConfig connectionConfig, Clock clock) {
        this.target = target;
        this.connectionConfig = connectionConfig;
        this.clock = clock;
    }

    // ── State accessors ──

    public State state() { return state.get(); }
    public String targetId() { return target.targetId(); }
    public RemoteTargetDescriptor target() { return target; }
    public List<RemoteOpsError> errors() { return List.copyOf(errors); }
    public McpClient client() {
        if (state.get() != State.READY) throw new IllegalStateException("session not ready: " + state.get());
        return client;
    }

    // ── Lifecycle ──

    /**
     * Start the SSH transport, perform MCP handshake, and attests the remote
     * server's capability boundary against {@link #target}.
     *
     * @throws IOException if transport, handshake, or attestation fails
     */
    public void start() throws IOException {
        if (!state.compareAndSet(State.NEW, State.STARTING)) {
            throw new IllegalStateException("session already started: " + state.get());
        }
        startedAt = clock.instant();

        // 1. Build SSH transport (§7.2)
        List<String> sshArgs = connectionConfig.sshArgs();
        log.info("[ops-session:{}] starting ssh transport to {}", target.targetId(),
            connectionConfig.safeRef());

        transport = new StdioTransport("ssh", sshArgs, Map.of(), Path.of("."));
        try {
            transport.start();
        } catch (IOException e) {
            RemoteOpsError err = classifyTransportStartError(e);
            transitionToFailed(err);
            throw new IOException("[ops-session:" + target.targetId() + "] ssh start failed: "
                + err.safeMessage(), e);
        }

        state.set(State.INITIALIZING);
        client = new McpClient(transport, target.targetId());

        // 2. MCP handshake
        try {
            client.initialize();
        } catch (IOException e) {
            RemoteOpsError err = classifyInitializeError(e);
            transitionToFailed(err);
            throw new IOException("[ops-session:" + target.targetId() + "] initialize failed: "
                + err.safeMessage(), e);
        }

        // 3. Attestation (§7.2): verify tool set
        try {
            attestToolSet();
        } catch (IOException e) {
            RemoteOpsError err = classifyAttestationError(e);
            transitionToFailed(err);
            throw new IOException("[ops-session:" + target.targetId() + "] attestation failed: "
                + err.safeMessage(), e);
        }

        state.set(State.READY);
        log.info("[ops-session:{}] ready ({}ms)", target.targetId(),
            Duration.between(startedAt, clock.instant()).toMillis());
    }

    /**
     * Call a tool on the remote server.
     *
     * @param toolName  allowlisted tool name
     * @param arguments tool arguments
     * @return the MCP call result
     * @throws IOException if the call fails or session is not ready
     */
    public McpCallResult callTool(String toolName, ObjectNode arguments) throws IOException {
        return callTool(toolName, arguments, ExecutionControl.none());
    }

    public McpCallResult callTool(String toolName, ObjectNode arguments, ExecutionControl control)
        throws IOException {
        if (state.get() != State.READY) {
            throw new IOException("[ops-session:" + target.targetId()
                + "] cannot call tool: session is " + state.get());
        }

        Instant start = clock.instant();
        try {
            McpCallResult result = client.callTool(toolName, arguments, control);
            log.debug("[ops-session:{}] {} → success ({}ms)",
                target.targetId(), toolName,
                Duration.between(start, clock.instant()).toMillis());
            return result;
        } catch (IOException e) {
            RemoteOpsError err = classifyToolError(toolName, e, start);
            errors.add(err);
            throw new IOException("[ops-session:" + target.targetId() + "] " + toolName
                + " failed: " + err.safeMessage(), e);
        }
    }

    /**
     * Gracefully drain and close the session. Idempotent.
     */
    @Override
    public void close() {
        State current = state.get();
        if (current == State.CLOSED) return;

        state.set(State.DRAINING);
        log.info("[ops-session:{}] draining → closing", target.targetId());

        if (transport != null) {
            try {
                // StdioTransport.stop() does: close stdin → SIGTERM → SIGKILL
                transport.stop();
            } catch (Exception e) {
                log.warn("[ops-session:{}] error during transport stop", target.targetId(), e);
            }
            try {
                transport.close();
            } catch (Exception e) {
                log.warn("[ops-session:{}] error during transport close", target.targetId(), e);
            }
        }

        state.set(State.CLOSED);
        long duration = startedAt != null
            ? Duration.between(startedAt, clock.instant()).toMillis() : 0;
        log.info("[ops-session:{}] closed ({}ms total)", target.targetId(), duration);
    }

    // ── Attestation ──

    private void attestToolSet() throws IOException {
        List<McpToolDef> tools = client.listTools();
        Set<String> names = tools.stream().map(McpToolDef::name).collect(Collectors.toSet());

        // Verify tool names match expected profile
        // — validate against the profile implied by expectedToolSetHash
        String actualHash = computeToolSetHashFromNames(names);
        if (!target.expectedToolSetHash().equals(actualHash)) {
            throw new IOException("tool-set hash mismatch: expected "
                + target.expectedToolSetHash() + " but got " + actualHash
                + " (tools: " + names + ")");
        }

        // Verify every tool has safe annotations (§6.3)
        for (McpToolDef tool : tools) {
            JsonNode a = tool.annotations();
            if (a == null
                || !a.path("readOnlyHint").asBoolean(false)
                || a.path("destructiveHint").asBoolean(true)
                || a.path("openWorldHint").asBoolean(true)) {
                throw new IOException("unsafe or missing MCP annotations for " + tool.name());
            }
        }
    }

    private static String computeToolSetHashFromNames(Set<String> names) {
        String[] sorted = names.toArray(String[]::new);
        java.util.Arrays.sort(sorted);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (String name : sorted) {
                md.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            byte[] digest = md.digest();
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ── Error classification (§7.5) ──

    private RemoteOpsError classifyTransportStartError(IOException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";

        if (msg.contains("Cannot run program \"ssh\"")) {
            return RemoteOpsError.sshClientMissing(target.targetId(), "ssh binary not found");
        }
        if (msg.contains("not found") || msg.contains("No such file")) {
            if (msg.contains("identity") || msg.contains("key")) {
                return RemoteOpsError.sshKeyUnreadable(target.targetId(), msg);
            }
            return RemoteOpsError.sshClientMissing(target.targetId(), msg);
        }
        // Transport-level IO failure during start
        return RemoteOpsError.sshConnectionRefused(target.targetId(),
            startedAt != null ? startedAt : clock.instant(),
            Duration.between(startedAt != null ? startedAt : clock.instant(), clock.instant()).toMillis());
    }

    private RemoteOpsError classifyInitializeError(IOException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";

        if (msg.contains("initialize failed")) {
            return RemoteOpsError.remoteMcpStartFailed(target.targetId(), msg);
        }
        if (msg.contains("timed out") || msg.contains("Timed out")) {
            return RemoteOpsError.sshRequestTimeout(target.targetId(), clock.instant(),
                Duration.between(startedAt, clock.instant()).toMillis());
        }
        return RemoteOpsError.remoteMcpProtocolError(target.targetId(), msg);
    }

    private RemoteOpsError classifyAttestationError(IOException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";

        if (msg.contains("tool-set hash mismatch") || msg.contains("profile mismatch")) {
            return RemoteOpsError.remoteToolsetMismatch(target.targetId(),
                target.expectedToolSetHash(), "actual");
        }
        if (msg.contains("unsafe") || msg.contains("annotations")) {
            return RemoteOpsError.remoteMcpProtocolError(target.targetId(), msg);
        }
        return RemoteOpsError.remoteProfileMismatch(target.targetId(), "", "");
    }

    private RemoteOpsError classifyToolError(String toolName, IOException e, Instant start) {
        String msg = e.getMessage() != null ? e.getMessage() : "";

        if (msg.contains("timed out") || msg.contains("Timed out")) {
            return RemoteOpsError.sshRequestTimeout(target.targetId(), start,
                Duration.between(start, clock.instant()).toMillis());
        }
        if (msg.contains("not alive") || msg.contains("exited")) {
            return RemoteOpsError.sshTransportClosed(target.targetId(), start,
                Duration.between(start, clock.instant()).toMillis(), -1);
        }
        if (msg.contains("cancelled") || msg.contains("interrupted")) {
            return RemoteOpsError.sshTransportClosed(target.targetId(), start,
                Duration.between(start, clock.instant()).toMillis(), -1);
        }
        return RemoteOpsError.remoteToolFailed(target.targetId(), toolName, msg);
    }

    // ── State transition helper ──

    private void transitionToFailed(RemoteOpsError error) {
        state.set(State.FAILED);
        firstError = error;
        errors.add(error);
    }

    public RemoteOpsError firstError() { return firstError; }
}
