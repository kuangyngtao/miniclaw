package com.clawkit.ops.loop;

import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.mcp.McpCallResult;
import com.clawkit.tools.mcp.McpClient;
import com.clawkit.tools.mcp.McpToolDef;
import com.clawkit.tools.mcp.McpTransport;
import com.clawkit.tools.remote.CredentialRef;
import com.clawkit.tools.remote.RemoteEndpointConfig;
import com.clawkit.tools.remote.RemoteError;
import com.clawkit.tools.remote.RemoteMcpSession;
import com.clawkit.ops.mcp.OpsCapabilityProfile;
import com.clawkit.ops.mcp.OpsMcpServer;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the lifecycle of a single SSH/MCP stdio session to a remote
 * OPS target.
 *
 * <p>This is now a thin adapter over {@link RemoteMcpSession} (in
 * {@code clawkit-tools}). It accepts the existing OPS-specific
 * {@link RemoteTargetDescriptor} and {@link SshConnectionConfig},
 * converts them to the generic types, and delegates all SSH/MCP
 * lifecycle operations.
 *
 * <p>Public API, error semantics, and state names are preserved for
 * backward compatibility with OPS workflows.
 *
 * <p>Design doc §7.3–§7.4, PR-M2 §4; REMOTE-0 §14.
 */
public final class RemoteOpsSession implements OpsReadSession {

    private static final Logger log = LoggerFactory.getLogger(RemoteOpsSession.class);
    static final String PROTOCOL_VERSION = "2024-11-05";
    static final String OPS_SERVER_NAME = "clawkit-ops-mcp";

    public enum State {
        NEW, STARTING, INITIALIZING, READY, DRAINING, FAILED, CLOSED
    }

    // ── OPS-specific fields (kept for backward compat) ──────────────
    private final RemoteTargetDescriptor target;
    private final SshConnectionConfig connectionConfig;
    private final Clock clock;
    private final List<RemoteOpsError> errors = new ArrayList<>();

    // ── Generic delegate ────────────────────────────────────────────
    private final com.clawkit.tools.remote.RemoteTargetDescriptor genericTarget;
    private final RemoteEndpointConfig genericEndpoint;
    private RemoteMcpSession delegate;
    private RemoteOpsError firstError;
    private Instant startedAt;

    // ── Constructors ──────────────────────────────────────────────────

    public RemoteOpsSession(RemoteTargetDescriptor target, SshConnectionConfig connectionConfig) {
        this(target, connectionConfig, Clock.systemUTC());
    }

    public RemoteOpsSession(RemoteTargetDescriptor target, SshConnectionConfig connectionConfig,
                             Clock clock) {
        this.target = target;
        this.connectionConfig = connectionConfig;
        this.clock = clock;

        // Build generic types from OPS-specific types
        this.genericTarget = new com.clawkit.tools.remote.RemoteTargetDescriptor(
            target.targetId(),
            OPS_SERVER_NAME,
            PROTOCOL_VERSION,
            target.expectedProbeVersion(),
            target.capabilityProfile(),
            target.expectedToolSetHash(),
            target.expectedToolContractHash()
        );

        CredentialRef keyRef = new CredentialRef.FileRef(connectionConfig.identityFile());
        this.genericEndpoint = new RemoteEndpointConfig(
            connectionConfig.host(),
            connectionConfig.port(),
            connectionConfig.user(),
            keyRef,
            connectionConfig.knownHostsFile(),
            connectionConfig.connectTimeout(),
            connectionConfig.requestTimeout(),
            connectionConfig.maxOutputBytes()
        );
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

        this.genericTarget = new com.clawkit.tools.remote.RemoteTargetDescriptor(
            target.targetId(),
            OPS_SERVER_NAME,
            PROTOCOL_VERSION,
            target.expectedProbeVersion(),
            target.capabilityProfile(),
            target.expectedToolSetHash(),
            target.expectedToolContractHash()
        );

        CredentialRef keyRef = new CredentialRef.FileRef(connectionConfig.identityFile());
        this.genericEndpoint = new RemoteEndpointConfig(
            connectionConfig.host(), connectionConfig.port(),
            connectionConfig.user(), keyRef,
            connectionConfig.knownHostsFile(),
            connectionConfig.connectTimeout(),
            connectionConfig.requestTimeout(),
            connectionConfig.maxOutputBytes()
        );

        this.delegate = new RemoteMcpSession(genericTarget, genericEndpoint, clock,
            transport, client);
    }

    // ── State accessors ───────────────────────────────────────────────

    public State state() {
        if (delegate == null) return State.NEW;
        return mapState(delegate.internalState());
    }

    @Override
    public boolean isReady() { return state() == State.READY; }

    @Override
    public String targetId() { return target.targetId(); }
    public RemoteTargetDescriptor target() { return target; }
    public List<RemoteOpsError> errors() { return List.copyOf(errors); }
    public RemoteOpsError firstError() { return firstError; }

    // ── Production lifecycle ──────────────────────────────────────────

    /**
     * Start the SSH transport, perform MCP handshake, and strictly attest
     * the remote server against {@link #target}.
     *
     * @throws IOException if transport, handshake, or attestation fails
     */
    public void start() throws IOException {
        if (delegate != null) {
            throw new IllegalStateException("session already started: " + target.targetId());
        }
        startedAt = clock.instant();
        delegate = new RemoteMcpSession(genericTarget, genericEndpoint, clock);

        try {
            delegate.start();
        } catch (IOException e) {
            RemoteOpsError err = classifyStartError(e);
            transitionToFailed(err);
            throw e;
        }
    }

    /**
     * Test-only: complete the handshake + attestation on the injected transport.
     * Delegates to the generic session's internal init+attest via a dedicated
     * public pathway, then maps errors back to OPS error types.
     */
    void doInitializeAndAttest() throws IOException {
        if (delegate == null) throw new IllegalStateException("delegate not created");
        startedAt = clock.instant();
        try {
            delegate.doInitializeAndAttestForAdapter();
        } catch (IOException e) {
            RemoteOpsError err = classifyStartError(e);
            transitionToFailed(err);
            throw e;
        }
    }

    // ── Tool calls ────────────────────────────────────────────────────

    public McpCallResult callTool(String toolName, ObjectNode arguments) throws IOException {
        return callTool(toolName, arguments,
            new DeadlineControl(connectionConfig.requestTimeout(), clock.instant()));
    }

    /** Call a tool with per-spec timeout from EvidenceSpec. */
    public McpCallResult callToolWithTimeout(String toolName, ObjectNode arguments,
                                              Duration timeout) throws IOException {
        return callTool(toolName, arguments,
            new DeadlineControl(timeout, clock.instant()));
    }

    public McpCallResult callTool(String toolName, ObjectNode arguments,
                                   ExecutionControl control) throws IOException {
        if (delegate == null) {
            throw new IOException("[ops-session:" + target.targetId()
                + "] cannot call tool: session is not started");
        }
        try {
            return delegate.callTool(toolName, arguments, control);
        } catch (IOException e) {
            RemoteOpsError err = classifyToolError(toolName, e,
                clock.instant());
            errors.add(err);
            throw new IOException("[ops-session:" + target.targetId() + "] "
                + toolName + " failed: " + err.safeMessage(), e);
        }
    }

    // ── Close ─────────────────────────────────────────────────────────

    /**
     * Gracefully drain and close the session. Idempotent.
     */
    @Override
    public void close() {
        if (delegate != null) {
            delegate.close();
        }
        State current = state();
        if (current == State.CLOSED) return;

        if (delegate == null) {
            // was never started
        }

        long duration = startedAt != null
            ? Duration.between(startedAt, clock.instant()).toMillis() : 0;
        log.info("[ops-session:{}] closed ({}ms total), firstError={}",
            target.targetId(), duration, firstError != null ? firstError.code() : "none");
    }

    // ── State mapping ─────────────────────────────────────────────────

    private static State mapState(RemoteMcpSession.InternalState s) {
        return switch (s) {
            case NEW -> State.NEW;
            case STARTING -> State.STARTING;
            case INITIALIZING -> State.INITIALIZING;
            case READY -> State.READY;
            case DRAINING -> State.DRAINING;
            case FAILED -> State.FAILED;
            case CLOSED -> State.CLOSED;
        };
    }

    // ── Error classification (preserved from original) ───────────────

    private RemoteOpsError classifyStartError(IOException e) {
        RemoteError re = delegate != null ? delegate.firstError() : null;
        String msg = e.getMessage() != null ? e.getMessage() : "";

        if (re != null) {
            return mapRemoteError(re);
        }

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

    private RemoteOpsError mapRemoteError(RemoteError re) {
        String code = re.code();
        return switch (code) {
            case "RMT-001" -> new RemoteOpsError(RemoteOpsError.Layer.LOCAL_CONFIG,
                "TARGET_NOT_FOUND", re.safeMessage(), re.retryable(),
                target.targetId(), re.at(), 0);
            case "RMT-002" -> new RemoteOpsError(RemoteOpsError.Layer.LOCAL_CONFIG,
                "TARGET_CONFIG_INVALID", re.safeMessage(), re.retryable(),
                target.targetId(), re.at(), 0);
            case "RMT-003" -> new RemoteOpsError(RemoteOpsError.Layer.LOCAL_CONFIG,
                "CREDENTIAL_REF_UNRESOLVED", re.safeMessage(), false,
                target.targetId(), re.at(), 0);
            case "RMT-004" -> RemoteOpsError.sshHostKeyRejected(target.targetId(),
                connectionConfig.safeRef());
            case "RMT-005" -> RemoteOpsError.sshAuthFailed(target.targetId(), re.at(),
                Duration.between(startedAt, re.at()).toMillis());
            case "RMT-006" -> RemoteOpsError.sshConnectionRefused(target.targetId(),
                re.at(), Duration.between(startedAt, re.at()).toMillis());
            case "RMT-007" -> RemoteOpsError.remoteMcpProtocolError(target.targetId(),
                re.safeMessage());
            case "RMT-008" -> RemoteOpsError.remoteProfileMismatch(target.targetId(),
                target.expectedProbeVersion(), re.safeMessage());
            case "RMT-009" -> RemoteOpsError.remoteProfileMismatch(target.targetId(),
                target.capabilityProfile(), re.safeMessage());
            case "RMT-010" -> RemoteOpsError.remoteToolsetMismatch(target.targetId(),
                target.expectedToolSetHash(), re.safeMessage());
            case "RMT-011" -> RemoteOpsError.remoteMcpProtocolError(target.targetId(),
                re.safeMessage());
            default -> RemoteOpsError.remoteMcpProtocolError(target.targetId(),
                re.safeMessage());
        };
    }

    private void transitionToFailed(RemoteOpsError error) {
        if (firstError == null) firstError = error;
        errors.add(error);
    }
}
