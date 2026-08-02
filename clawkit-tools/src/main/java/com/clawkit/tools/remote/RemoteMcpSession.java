package com.clawkit.tools.remote;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic SSH/MCP session for a single remote target.
 *
 * <p>Manages the full lifecycle: SSH transport start, MCP handshake,
 * strict attestation against a pinned {@link RemoteTargetDescriptor},
 * tool calls, and graceful shutdown.
 *
 * <p>This is the GENERIC session — no OPS-specific server name,
 * profile knowledge, or incident workflow. The OPS {@code RemoteOpsSession}
 * is a thin adapter over this class.
 *
 * <p>Consumes {@link RemoteSshConnectionSpec} to support both legacy
 * explicit endpoint mode ({@link RemoteEndpointConfig}) and
 * OpenSSH alias mode ({@link OpenSshAliasConnectionSpec}).
 *
 * <h3>State machine</h3>
 * <pre>{@code
 *   NEW -> STARTING -> INITIALIZING -> READY
 *   Any  -> FAILED
 *   Any  -> DRAINING -> CLOSED
 * }</pre>
 *
 * <h3>Attestation sequence</h3>
 * <ol>
 *   <li>SSH transport started</li>
 *   <li>MCP initialize — protocolVersion exact match</li>
 *   <li>serverName exact match</li>
 *   <li>probeVersion exact match</li>
 *   <li>capabilityProfile exact match</li>
 *   <li>initialize toolSetHash exact match</li>
 *   <li>tools/list succeeds</li>
 *   <li>recomputed toolSetHash exact match</li>
 *   <li>tool names exactly equal to expected profile</li>
 *   <li>every annotation is safe</li>
 *   <li>canonical tool contract hash exact match</li>
 *   <li>→ READY</li>
 * </ol>
 *
 * <p>Any mismatch → FAILED, transport closed immediately.
 *
 * <p>Design: REMOTE-0 §7–§8, PRODUCT-1 §5.2.
 */
public final class RemoteMcpSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RemoteMcpSession.class);

    /**
     * SSH environment allowlist. Only these variable names may be forwarded
     * to the SSH subprocess. Includes Agent (SSH_AUTH_SOCK, SSH_AGENT_PID),
     * platform basics (PATH, HOME, TEMP), and locale. Excludes API tokens,
     * webhooks, and all application secrets.
     */
    public static final java.util.Set<String> SSH_ENV_ALLOWLIST = java.util.Set.of(
        "PATH", "HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
        "SYSTEMROOT", "COMSPEC", "SSH_AUTH_SOCK", "SSH_AGENT_PID",
        "LANG", "LC_ALL", "TEMP", "TMP", "TMPDIR"
    );

    public enum InternalState {
        NEW, STARTING, INITIALIZING, READY, DRAINING, FAILED, CLOSED
    }

    private final RemoteTargetDescriptor target;
    private final RemoteSshConnectionSpec connectionSpec;
    private final Clock clock;
    private final java.util.concurrent.atomic.AtomicReference<InternalState> state =
        new java.util.concurrent.atomic.AtomicReference<>(InternalState.NEW);
    private final java.util.concurrent.atomic.AtomicLong generation =
        new java.util.concurrent.atomic.AtomicLong(0);
    private final List<RemoteError> errors = new ArrayList<>();

    private McpTransport transport;
    private McpClient client;
    private Instant startedAt;
    private RemoteError firstError;
    private Duration requestTimeout;
    private int maxOutputBytes;

    // Attestation results (populated on READY)
    private volatile List<McpToolDef> attestedTools = List.of();
    private volatile RemoteAttestationSnapshot attestationSnapshot;

    // ── Constructors ──────────────────────────────────────────────────

    /** Primary constructor — consumes the unified connection spec. */
    public RemoteMcpSession(RemoteTargetDescriptor target, RemoteSshConnectionSpec connectionSpec) {
        this(target, connectionSpec, Clock.systemUTC());
    }

    /** Constructor with explicit clock. */
    public RemoteMcpSession(RemoteTargetDescriptor target, RemoteSshConnectionSpec connectionSpec,
                             Clock clock) {
        this.target = target;
        this.connectionSpec = connectionSpec;
        this.clock = clock;
        this.requestTimeout = connectionSpec.requestTimeout();
        this.maxOutputBytes = connectionSpec.maxOutputBytes();
    }

    /**
     * Backward-compatible constructor accepting {@link RemoteEndpointConfig}.
     * @deprecated prefer {@link #RemoteMcpSession(RemoteTargetDescriptor, RemoteSshConnectionSpec)}
     */
    @Deprecated
    public RemoteMcpSession(RemoteTargetDescriptor target, RemoteEndpointConfig endpointConfig) {
        this(target, (RemoteSshConnectionSpec) endpointConfig, Clock.systemUTC());
    }

    /**
     * Backward-compatible constructor accepting {@link RemoteEndpointConfig} with clock.
     * @deprecated prefer {@link #RemoteMcpSession(RemoteTargetDescriptor, RemoteSshConnectionSpec, Clock)}
     */
    @Deprecated
    public RemoteMcpSession(RemoteTargetDescriptor target, RemoteEndpointConfig endpointConfig,
                             Clock clock) {
        this(target, (RemoteSshConnectionSpec) endpointConfig, clock);
    }

    /**
     * Create a session with a pre-built transport and client.
     * Skips SSH process creation. Transport is assumed already started.
     * Useful for testing with fake transports and for adapters.
     */
    public RemoteMcpSession(RemoteTargetDescriptor target, RemoteEndpointConfig endpointConfig,
                            Clock clock, McpTransport transport, McpClient client) {
        this.target = target;
        this.connectionSpec = endpointConfig;
        this.clock = clock;
        this.transport = transport;
        this.client = client;
        this.requestTimeout = endpointConfig.requestTimeout();
        this.maxOutputBytes = endpointConfig.maxOutputBytes();
        state.set(InternalState.INITIALIZING);
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public InternalState internalState() { return state.get(); }
    public String targetId() { return target.targetId(); }
    public RemoteTargetDescriptor target() { return target; }
    public long generation() { return generation.get(); }
    public List<RemoteError> errors() { return List.copyOf(errors); }
    public RemoteError firstError() { return firstError; }
    public List<McpToolDef> attestedTools() { return attestedTools; }

    /** Attestation snapshot — only non-null after READY. */
    public RemoteAttestationSnapshot attestationSnapshot() { return attestationSnapshot; }

    /** True if the session is in READY state. */
    public boolean isReady() { return state.get() == InternalState.READY; }

    // ── Production lifecycle ──────────────────────────────────────────

    /**
     * Start the SSH transport, perform MCP handshake, and strictly attest
     * the remote server against the target descriptor.
     *
     * @throws IOException if transport, handshake, or attestation fails
     */
    public void start() throws IOException {
        if (!state.compareAndSet(InternalState.NEW, InternalState.STARTING)) {
            throw new IllegalStateException(
                "session already started: " + target.targetId() + " state=" + state.get());
        }
        generation.incrementAndGet();
        startedAt = clock.instant();
        long connectStart = startedAt.toEpochMilli();

        // 1. Build SSH transport
        List<String> sshArgs = connectionSpec.sshArgs();
        log.info("[remote:{}] starting ssh transport to {}", target.targetId(),
            connectionSpec.safeRef());

        transport = new StdioTransport("ssh", sshArgs, buildSshEnv(), Path.of("."));
        try {
            transport.start();
        } catch (IOException e) {
            RemoteError err = classifyTransportStartError(e);
            transitionToFailed(err);
            throw new IOException(err.safeMessage(), e);
        }

        long connectEnd = clock.instant().toEpochMilli();
        state.set(InternalState.INITIALIZING);
        client = new McpClient(transport, target.targetId());

        // 2. Strict MCP handshake + attestation
        try {
            doInitializeAndAttest(connectStart, connectEnd);
        } catch (IOException e) {
            // Error already classified and stored by doInitializeAndAttest
            closeTransport();
            throw e;
        }

        state.set(InternalState.READY);
        log.info("[remote:{}] ready ({}ms total)", target.targetId(),
            Duration.between(startedAt, clock.instant()).toMillis());
    }

    /**
     * Public entry point for adapters that have already set up the transport.
     * Called by OPS {@code RemoteOpsSession.doInitializeAndAttest()} for the
     * test-only pre-built transport path.
     */
    public void doInitializeAndAttestForAdapter() throws IOException {
        if (state.get() != InternalState.INITIALIZING) {
            throw new IllegalStateException("not in INITIALIZING: " + state.get());
        }
        startedAt = clock.instant();
        long now = clock.instant().toEpochMilli();
        doInitializeAndAttest(now, now);
        state.set(InternalState.READY);
    }

    private void doInitializeAndAttest(long connectStartMs, long connectEndMs) throws IOException {
        ExecutionControl control = new com.clawkit.tools.control.TimeoutExecutionControl(requestTimeout);

        // ── MCP initialize ────────────────────────────────────────────
        McpInitializeResult info;
        try {
            info = client.initialize(control);
        } catch (IOException e) {
            RemoteError err = classifyInitializeError(e);
            transitionToFailed(err);
            throw new IOException(err.safeMessage(), e);
        }

        // protocolVersion exact match
        if (!target.expectedProtocolVersion().equals(info.protocolVersion())) {
            RemoteError err = RemoteError.mcpProtocolMismatch(target.targetId(),
                target.expectedProtocolVersion(), info.protocolVersion());
            transitionToFailed(err);
            throw new IOException(err.safeMessage());
        }

        // serverName exact match
        if (!target.expectedServerName().equals(info.serverName())) {
            RemoteError err = RemoteError.serverIdentityMismatch(target.targetId(),
                "serverName", target.expectedServerName(), info.serverName());
            transitionToFailed(err);
            throw new IOException(err.safeMessage());
        }

        // probeVersion exact match
        String probeVersion = info.serverInfoText("probeVersion");
        if (!target.expectedProbeVersion().equals(probeVersion)) {
            RemoteError err = RemoteError.serverIdentityMismatch(target.targetId(),
                "probeVersion", target.expectedProbeVersion(), probeVersion);
            transitionToFailed(err);
            throw new IOException(err.safeMessage());
        }

        // capabilityProfile exact match
        String capabilityProfile = info.serverInfoText("capabilityProfile");
        if (!target.expectedCapabilityProfile().equals(capabilityProfile)) {
            RemoteError err = RemoteError.capabilityProfileMismatch(target.targetId(),
                target.expectedCapabilityProfile(), capabilityProfile);
            transitionToFailed(err);
            throw new IOException(err.safeMessage());
        }

        // initialize toolSetHash exact match
        String initHash = info.serverInfoText("toolSetHash");
        if (!target.expectedToolSetHash().equals(initHash)) {
            RemoteError err = RemoteError.toolContractMismatch(target.targetId(),
                "initialize", target.expectedToolSetHash(), initHash);
            transitionToFailed(err);
            throw new IOException(err.safeMessage());
        }

        // ── tools/list and attestation ────────────────────────────────
        List<McpToolDef> tools;
        try {
            tools = client.listTools();
        } catch (IOException e) {
            RemoteError err = classifyAttestationError(e);
            transitionToFailed(err);
            throw new IOException(err.safeMessage());
        }

        // Recompute tool-set hash from actual tool list
        String listHash = ToolContractHash.computeToolSetHashFromTools(tools);
        if (!target.expectedToolSetHash().equals(listHash)) {
            RemoteError err = RemoteError.toolContractMismatch(target.targetId(),
                "tools/list", target.expectedToolSetHash(), listHash);
            transitionToFailed(err);
            throw new IOException(err.safeMessage());
        }

        // Every tool must have safe annotations
        for (McpToolDef tool : tools) {
            JsonNode a = tool.annotations();
            if (a == null
                || !a.path("readOnlyHint").asBoolean(false)
                || a.path("destructiveHint").asBoolean(true)
                || a.path("openWorldHint").asBoolean(true)) {
                RemoteError err = RemoteError.unsafeToolAnnotation(target.targetId(), tool.name());
                transitionToFailed(err);
                throw new IOException(err.safeMessage());
            }
            // idempotentHint must be true if present
            if (a.has("idempotentHint") && !a.path("idempotentHint").asBoolean(true)) {
                RemoteError err = RemoteError.unsafeToolAnnotation(target.targetId(), tool.name());
                transitionToFailed(err);
                throw new IOException(err.safeMessage());
            }
        }

        // Compute and verify tool-contract hash (must be pre-pinned)
        String computedContractHash = ToolContractHash.computeFromMcpTools(tools);
        if (!target.expectedToolContractHash().equals(computedContractHash)) {
            RemoteError err = RemoteError.toolContractMismatch(target.targetId(),
                "contract", target.expectedToolContractHash(), computedContractHash);
            transitionToFailed(err);
            throw new IOException(err.safeMessage());
        }

        // ── All checks passed ─────────────────────────────────────────
        long attestEnd = clock.instant().toEpochMilli();
        List<String> toolNames = tools.stream().map(McpToolDef::name).sorted().toList();

        this.attestedTools = List.copyOf(tools);
        this.attestationSnapshot = new RemoteAttestationSnapshot(
            target.targetId(),
            info.serverName(),
            info.protocolVersion(),
            probeVersion,
            capabilityProfile,
            initHash,
            listHash,
            computedContractHash,
            toolNames,
            connectEndMs - connectStartMs,
            attestEnd - connectEndMs,
            clock.instant()
        );

        log.info("[remote:{}] attestation complete: server={}, profile={}, tools={}, "
            + "connectLatency={}ms, attestLatency={}ms",
            target.targetId(), info.serverName(), capabilityProfile, toolNames,
            attestationSnapshot.connectLatencyMs(), attestationSnapshot.attestationLatencyMs());
    }

    // ── Tool calls ────────────────────────────────────────────────────

    /** Call a tool on the remote server. Only allowed in READY state. */
    public McpCallResult callTool(String toolName, ObjectNode arguments) throws IOException {
        return callTool(toolName, arguments,
            new com.clawkit.tools.control.TimeoutExecutionControl(requestTimeout));
    }

    /** Call a tool with a custom timeout. */
    public McpCallResult callToolWithTimeout(String toolName, ObjectNode arguments,
                                              Duration timeout) throws IOException {
        return callTool(toolName, arguments,
            new com.clawkit.tools.control.TimeoutExecutionControl(timeout));
    }

    /** Call a tool with an explicit ExecutionControl. */
    public McpCallResult callTool(String toolName, ObjectNode arguments,
                                   ExecutionControl control) throws IOException {
        if (state.get() != InternalState.READY) {
            throw new IOException("[remote:" + target.targetId()
                + "] cannot call tool: session is " + state.get());
        }

        Instant start = clock.instant();
        try {
            return client.callTool(toolName, arguments, control);
        } catch (IOException e) {
            RemoteError err = classifyToolError(toolName, e, start);
            errors.add(err);
            throw new IOException("[remote:" + target.targetId() + "] "
                + toolName + " failed: " + err.safeMessage(), e);
        }
    }

    // ── Close ─────────────────────────────────────────────────────────

    /**
     * Gracefully drain and close the session. Idempotent.
     * If the session was in FAILED, firstError is preserved.
     */
    @Override
    public void close() {
        InternalState current = state.get();
        if (current == InternalState.CLOSED) return;

        state.set(InternalState.DRAINING);
        closeTransport();
        state.set(InternalState.CLOSED);
        attestedTools = List.of();
        attestationSnapshot = null;

        long duration = startedAt != null
            ? Duration.between(startedAt, clock.instant()).toMillis() : 0;
        log.info("[remote:{}] closed ({}ms total), firstError={}",
            target.targetId(), duration, firstError != null ? firstError.code() : "none");
    }

    private void closeTransport() {
        if (transport != null) {
            try { transport.stop(); } catch (Exception ignored) { }
            try { transport.close(); } catch (Exception ignored) { }
        }
    }

    // ── Error classification ──────────────────────────────────────────

    private RemoteError classifyTransportStartError(IOException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.contains("Cannot run program \"ssh\"")) {
            return RemoteError.of("RMT-006", "ssh binary not found", false);
        }
        if (msg.contains("not found") || msg.contains("No such file")) {
            if (msg.contains("identity") || msg.contains("key")) {
                return RemoteError.of("RMT-003", "ssh key file not found", false);
            }
            return RemoteError.of("RMT-006", msg, false);
        }
        if (msg.contains("Connection refused")) {
            return RemoteError.remoteUnreachable(target.targetId(),
                Duration.between(startedAt, clock.instant()).toMillis());
        }
        return RemoteError.remoteUnreachable(target.targetId(),
            Duration.between(startedAt, clock.instant()).toMillis());
    }

    private RemoteError classifyInitializeError(IOException e) {
        String msg = diagnosticText(e);
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        if ((lower.contains("host key") || lower.contains("host identification"))
            && (lower.contains("changed") || lower.contains("mismatch")
                || lower.contains("offending"))) {
            return RemoteError.of("RMT-004", "remote host key changed", false,
                Map.of("hostKeyStatus", "changed"));
        }
        if (lower.contains("host key verification failed")
            || (lower.contains("host key") && lower.contains("unknown"))) {
            return RemoteError.of("RMT-004", "remote host key not recognized", false,
                Map.of("hostKeyStatus", "unknown"));
        }
        if (lower.contains("permission denied") || lower.contains("publickey")
            || (lower.contains("authentication") && lower.contains("fail"))) {
            return RemoteError.sshAuthFailed(target.targetId());
        }
        if (msg.contains("protocol version mismatch")) {
            return RemoteError.mcpProtocolMismatch(target.targetId(), "", msg);
        }
        if (msg.contains("server name mismatch")) {
            return RemoteError.serverIdentityMismatch(target.targetId(), "serverName", "", "");
        }
        if (msg.contains("probeVersion") || msg.contains("capabilityProfile")) {
            return RemoteError.serverIdentityMismatch(target.targetId(), "profile",
                target.expectedProbeVersion(), msg);
        }
        if (msg.contains("toolSetHash") || msg.contains("hash mismatch")) {
            return RemoteError.toolContractMismatch(target.targetId(),
                "initialize", target.expectedToolSetHash(), msg);
        }
        if (msg.contains("initialize failed")) {
            return RemoteError.of("RMT-007", "remote MCP server failed to start", true);
        }
        if (msg.contains("timed out") || msg.contains("Timed out")) {
            return RemoteError.remoteUnreachable(target.targetId(),
                Duration.between(startedAt, clock.instant()).toMillis());
        }
        return RemoteError.of("RMT-007", msg, false);
    }

    private String diagnosticText(IOException e) {
        String message = e.getMessage() != null ? e.getMessage() : "";
        if (transport instanceof StdioTransport stdio) {
            String diagnostics = stdio.sanitizedDiagnosticSummary();
            if (!diagnostics.isBlank()) return message + "\n" + diagnostics;
        }
        return message;
    }

    private RemoteError classifyAttestationError(IOException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.contains("hash mismatch") || msg.contains("contract mismatch")) {
            return RemoteError.toolContractMismatch(target.targetId(),
                "contract", target.expectedToolContractHash(), "actual");
        }
        if (msg.contains("unsafe") || msg.contains("annotation")) {
            return RemoteError.of("RMT-011", msg, false);
        }
        if (msg.contains("protocol version") || msg.contains("capabilityProfile")
            || msg.contains("probeVersion")) {
            return RemoteError.serverIdentityMismatch(target.targetId(),
                "profile", target.expectedProbeVersion(), msg);
        }
        return RemoteError.of("RMT-007", msg, false);
    }

    private RemoteError classifyToolError(String toolName, IOException e, Instant start) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.contains("timed out") || msg.contains("Timed out")) {
            return RemoteError.remoteUnreachable(target.targetId(),
                Duration.between(start, clock.instant()).toMillis());
        }
        if (msg.contains("not alive") || msg.contains("exited")) {
            return RemoteError.of("RMT-006", "transport closed during " + toolName, true);
        }
        return RemoteError.of("RMT-006", toolName + " failed: " + msg, true);
    }

    private void transitionToFailed(RemoteError error) {
        state.set(InternalState.FAILED);
        if (firstError == null) firstError = error;
        errors.add(error);
    }

    // ── SSH environment whitelist ─────────────────────────────────────

    /**
     * Build the environment for SSH subprocesses by filtering the parent
     * process environment against the allowlist.
     */
    private static Map<String, String> buildSshEnv() {
        Map<String, String> env = new java.util.LinkedHashMap<>();
        for (String key : SSH_ENV_ALLOWLIST) {
            String value = System.getenv(key);
            if (value != null) {
                env.put(key, value);
            }
        }
        return java.util.Collections.unmodifiableMap(env);
    }
}
