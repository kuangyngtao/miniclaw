package com.clawkit.ops.loop;

import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.mcp.McpCallResult;
import com.clawkit.tools.mcp.McpClient;
import com.clawkit.tools.mcp.McpInitializeResult;
import com.clawkit.tools.mcp.McpToolDef;
import com.clawkit.tools.mcp.McpTransport;
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
 * <p>Design doc §7.3–§7.4, PR-M2 §4. Strict attestation on every start:
 * protocolVersion, serverName, probeVersion, capabilityProfile,
 * initialize toolSetHash, tools/list toolSetHash, and annotations.
 * Any mismatch → FAILED, transport closed immediately.
 *
 * <p>State machine:
 *
 * <pre>{@code
 *   NEW -> STARTING -> INITIALIZING -> READY
 *   Any  -> FAILED
 *   Any  -> DRAINING -> CLOSED
 * }</pre>
 *
 * <p>DEFAULT_REQUEST_TIMEOUT is applied via {@link ExecutionControl}
 * for initialize, listTools, and callTool. The session does NOT use
 * {@code ControlMaster}.
 */
public final class RemoteOpsSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RemoteOpsSession.class);
    static final String PROTOCOL_VERSION = "2024-11-05";
    static final String OPS_SERVER_NAME = "clawkit-ops-mcp";

    public enum State {
        NEW, STARTING, INITIALIZING, READY, DRAINING, FAILED, CLOSED
    }

    private final RemoteTargetDescriptor target;
    private final SshConnectionConfig connectionConfig;
    private final Clock clock;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final List<RemoteOpsError> errors = new ArrayList<>();

    private McpTransport transport;
    private McpClient client;
    private Instant startedAt;
    private RemoteOpsError firstError;
    private Duration requestTimeout;
    private int maxOutputBytes;

    // ── Constructors ──

    public RemoteOpsSession(RemoteTargetDescriptor target, SshConnectionConfig connectionConfig) {
        this(target, connectionConfig, Clock.systemUTC());
    }

    public RemoteOpsSession(RemoteTargetDescriptor target, SshConnectionConfig connectionConfig, Clock clock) {
        this.target = target;
        this.connectionConfig = connectionConfig;
        this.clock = clock;
        this.requestTimeout = connectionConfig.requestTimeout();
        this.maxOutputBytes = connectionConfig.maxOutputBytes();
    }

    /**
     * Test-only: create a session with a pre-built transport and client.
     * Skips SSH process creation — useful for fake-transport testing.
     */
    RemoteOpsSession(RemoteTargetDescriptor target, SshConnectionConfig connectionConfig,
                     Clock clock, McpTransport transport, McpClient client) {
        this.target = target;
        this.connectionConfig = connectionConfig;
        this.clock = clock;
        this.transport = transport;
        this.client = client;
        this.requestTimeout = connectionConfig.requestTimeout();
        this.maxOutputBytes = connectionConfig.maxOutputBytes();
        // Transport is already started; jump to INITIALIZING
        state.set(State.INITIALIZING);
    }

    // ── State accessors ──

    public State state() { return state.get(); }
    public String targetId() { return target.targetId(); }
    public RemoteTargetDescriptor target() { return target; }
    public List<RemoteOpsError> errors() { return List.copyOf(errors); }
    public RemoteOpsError firstError() { return firstError; }

    // ── Production lifecycle ──

    /**
     * Start the SSH transport, perform MCP handshake, and strictly attest
     * the remote server against {@link #target}.
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
            throw e;
        }

        state.set(State.INITIALIZING);
        client = new McpClient(transport, target.targetId());

        // 2. Strict MCP handshake (§4.2)
        try {
            doInitialize();
        } catch (IOException e) {
            RemoteOpsError err = classifyInitializeError(e);
            transitionToFailed(err);
            closeTransport();
            throw e;
        }

        // 3. Tool-set attestation (§4.3)
        try {
            attestToolSet();
        } catch (IOException e) {
            RemoteOpsError err = classifyAttestationError(e);
            transitionToFailed(err);
            closeTransport();
            throw e;
        }

        state.set(State.READY);
        log.info("[ops-session:{}] ready ({}ms)", target.targetId(),
            Duration.between(startedAt, clock.instant()).toMillis());
    }

    /**
     * Test-only: complete the handshake + attestation on the injected transport.
     * Same fail-closed behavior as {@link #start()}: any mismatch transitions to
     * FAILED, closes transport, and throws IOException.
     */
    void doInitializeAndAttest() throws IOException {
        if (state.get() != State.INITIALIZING) {
            throw new IllegalStateException("not in INITIALIZING: " + state.get());
        }
        startedAt = clock.instant();
        try {
            doInitialize();
        } catch (IOException e) {
            transitionToFailed(classifyInitializeError(e));
            closeTransport();
            throw e;
        }
        try {
            attestToolSet();
        } catch (IOException e) {
            transitionToFailed(classifyAttestationError(e));
            closeTransport();
            throw e;
        }
        state.set(State.READY);
    }

    private void doInitialize() throws IOException {
        ExecutionControl control = new DeadlineControl(requestTimeout, clock.instant());
        McpInitializeResult info = client.initialize(control);

        // Strict: protocolVersion MUST be 2024-11-05 (§4.3.1)
        if (!PROTOCOL_VERSION.equals(info.protocolVersion())) {
            throw new IOException("protocol version mismatch: expected "
                + PROTOCOL_VERSION + " but got " + info.protocolVersion());
        }

        // Strict: serverName MUST be clawkit-ops-mcp (§4.3.2)
        if (!OPS_SERVER_NAME.equals(info.serverName())) {
            throw new IOException("server name mismatch: expected "
                + OPS_SERVER_NAME + " but got " + info.serverName());
        }

        // Strict: probeVersion MUST match expected (§4.3.3)
        String probeVersion = info.serverInfoText("probeVersion");
        if (!target.expectedProbeVersion().equals(probeVersion)) {
            throw new IOException("probeVersion mismatch: expected "
                + target.expectedProbeVersion() + " but got " + probeVersion);
        }

        // Strict: capabilityProfile MUST match expected (§4.3.4)
        String capabilityProfile = info.serverInfoText("capabilityProfile");
        if (!target.capabilityProfile().equals(capabilityProfile)) {
            throw new IOException("capabilityProfile mismatch: expected "
                + target.capabilityProfile() + " but got " + capabilityProfile);
        }

        // Strict: initialize toolSetHash MUST match pinned hash (§4.3.5)
        String initHash = info.serverInfoText("toolSetHash");
        if (!target.expectedToolSetHash().equals(initHash)) {
            throw new IOException("initialize toolSetHash mismatch: expected "
                + target.expectedToolSetHash() + " but got " + initHash);
        }

        log.info("[ops-session:{}] attestation: protocol={}, server={}, probe={}, profile={}",
            target.targetId(), info.protocolVersion(), info.serverName(),
            probeVersion, capabilityProfile);
    }

    // ── Tool calls ──

    public McpCallResult callTool(String toolName, ObjectNode arguments) throws IOException {
        return callTool(toolName, arguments, new DeadlineControl(requestTimeout, clock.instant()));
    }

    /** Call a tool with per-spec timeout from EvidenceSpec. */
    public McpCallResult callToolWithTimeout(String toolName, ObjectNode arguments,
                                              Duration timeout) throws IOException {
        return callTool(toolName, arguments, new DeadlineControl(timeout, clock.instant()));
    }

    public McpCallResult callTool(String toolName, ObjectNode arguments, ExecutionControl control)
        throws IOException {
        if (state.get() != State.READY) {
            throw new IOException("[ops-session:" + target.targetId()
                + "] cannot call tool: session is " + state.get());
        }

        Instant start = clock.instant();
        try {
            return client.callTool(toolName, arguments, control);
        } catch (IOException e) {
            RemoteOpsError err = classifyToolError(toolName, e, start);
            errors.add(err);
            throw new IOException("[ops-session:" + target.targetId() + "] "
                + toolName + " failed: " + err.safeMessage(), e);
        }
    }

    // ── Close ──

    /**
     * Gracefully drain and close the session. Idempotent.
     * If the session was in FAILED, firstError is preserved.
     */
    @Override
    public void close() {
        State current = state.get();
        if (current == State.CLOSED) return;

        state.set(State.DRAINING);
        closeTransport();
        state.set(State.CLOSED);

        long duration = startedAt != null
            ? Duration.between(startedAt, clock.instant()).toMillis() : 0;
        log.info("[ops-session:{}] closed ({}ms total), firstError={}",
            target.targetId(), duration, firstError != null ? firstError.code() : "none");
    }

    private void closeTransport() {
        if (transport != null) {
            try { transport.stop(); } catch (Exception ignored) { }
            try { transport.close(); } catch (Exception ignored) { }
        }
    }

    // ── Attestation ──

    private void attestToolSet() throws IOException {
        List<McpToolDef> tools = client.listTools();
        Set<String> names = tools.stream().map(McpToolDef::name).collect(Collectors.toSet());

        // Re-compute hash from actual tool list (§4.3.6)
        String listHash = computeToolSetHashFromNames(names);
        if (!target.expectedToolSetHash().equals(listHash)) {
            throw new IOException("tools/list hash mismatch: expected "
                + target.expectedToolSetHash() + " but got " + listHash);
        }

        // Every tool must have safe annotations (§4.3.7)
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

    static String computeToolSetHashFromNames(Set<String> names) {
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
        return RemoteOpsError.sshConnectionRefused(target.targetId(),
            startedAt != null ? startedAt : clock.instant(),
            Duration.between(startedAt != null ? startedAt : clock.instant(), clock.instant()).toMillis());
    }

    private RemoteOpsError classifyInitializeError(IOException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.contains("protocol version mismatch")) {
            return RemoteOpsError.remoteMcpProtocolError(target.targetId(), msg);
        }
        if (msg.contains("server name mismatch")) {
            return RemoteOpsError.remoteProfileMismatch(target.targetId(), "", "");
        }
        if (msg.contains("probeVersion mismatch") || msg.contains("capabilityProfile mismatch")) {
            return RemoteOpsError.remoteProfileMismatch(target.targetId(),
                target.expectedProbeVersion(), msg);
        }
        if (msg.contains("toolSetHash mismatch") || msg.contains("hash mismatch")) {
            return RemoteOpsError.remoteToolsetMismatch(target.targetId(),
                target.expectedToolSetHash(), msg);
        }
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
        if (msg.contains("hash mismatch") || msg.contains("profile mismatch")) {
            return RemoteOpsError.remoteToolsetMismatch(target.targetId(),
                target.expectedToolSetHash(), "actual");
        }
        if (msg.contains("unsafe") || msg.contains("annotations")) {
            return RemoteOpsError.remoteMcpProtocolError(target.targetId(), msg);
        }
        if (msg.contains("protocol version") || msg.contains("capabilityProfile")
            || msg.contains("probeVersion")) {
            return RemoteOpsError.remoteProfileMismatch(target.targetId(),
                target.expectedProbeVersion(), msg);
        }
        return RemoteOpsError.remoteMcpProtocolError(target.targetId(), msg);
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
        return RemoteOpsError.remoteToolFailed(target.targetId(), toolName, msg);
    }

    private void transitionToFailed(RemoteOpsError error) {
        state.set(State.FAILED);
        if (firstError == null) firstError = error;
        errors.add(error);
    }
}
