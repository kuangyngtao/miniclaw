package com.clawkit.cli.remote;

import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolMount;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.mcp.McpClient;
import com.clawkit.tools.mcp.McpToolAdapter;
import com.clawkit.tools.mcp.McpToolDef;
import com.clawkit.tools.remote.CredentialRef;
import com.clawkit.tools.remote.RemoteAttestationSnapshot;
import com.clawkit.tools.remote.RemoteConnectionSnapshot;
import com.clawkit.tools.remote.RemoteConnectionState;
import com.clawkit.tools.remote.RemoteEndpointConfig;
import com.clawkit.tools.remote.RemoteError;
import com.clawkit.tools.remote.RemoteMcpSession;
import com.clawkit.tools.remote.RemoteTargetDescriptor;
import com.clawkit.tools.remote.RemoteSshConnectionSpec;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the lifecycle of a single active remote MCP connection.
 *
 * <p>Only one target can be active at a time. Handles:
 * <ul>
 *   <li>Connection state machine</li>
 *   <li>Generation-based stale tool prevention</li>
 *   <li>Atomic tool mount/unmount in the ToolRegistry</li>
 *   <li>Target-bound tool adapter wrapping</li>
 * </ul>
 *
 * <p>Design: REMOTE-0 §9–§10.
 */
public class RemoteConnectionService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RemoteConnectionService.class);

    private final RemoteTargetStore store;
    private final ToolRegistry registry;
    private final Clock clock;
    private final ReentrantLock connectLock = new ReentrantLock();
    private final AtomicReference<String> activeTargetId = new AtomicReference<>();
    private final AtomicLong generation = new AtomicLong(0);

    private volatile RemoteMcpSession session;
    private volatile ToolMount toolMount;
    private volatile RemoteConnectionState state = RemoteConnectionState.DISCONNECTED;
    private volatile RemoteConnectionSnapshot lastSnapshot;
    private volatile RemoteError lastError;

    public RemoteConnectionService(RemoteTargetStore store, ToolRegistry registry) {
        this(store, registry, Clock.systemUTC());
    }

    public RemoteConnectionService(RemoteTargetStore store, ToolRegistry registry, Clock clock) {
        this.store = store;
        this.registry = registry;
        this.clock = clock;
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public RemoteConnectionState state() { return state; }
    public String activeTargetId() { return activeTargetId.get(); }
    public long generation() { return generation.get(); }
    public Optional<RemoteConnectionSnapshot> lastSnapshot() {
        return Optional.ofNullable(lastSnapshot);
    }
    public Optional<RemoteError> lastError() { return Optional.ofNullable(lastError); }

    // ── Connect ───────────────────────────────────────────────────────

    /**
     * Connect to a registered target. Fails if another target is already active.
     *
     * @return the attestation snapshot on success
     * @throws IOException if connection or attestation fails
     */
    public RemoteAttestationSnapshot connect(String targetId) throws IOException {
        // Check another target isn't already active
        String current = activeTargetId.get();
        if (current != null && !current.equals(targetId)) {
            RemoteError err = RemoteError.activeTargetExists(current);
            lastError = err;
            throw new IOException(err.safeMessage());
        }

        // Idempotent: if already connected to the same target, return snapshot
        if (current != null && current.equals(targetId) && state == RemoteConnectionState.READY) {
            var snap = session != null ? session.attestationSnapshot() : null;
            if (snap != null) return snap;
        }

        connectLock.lock();
        try {
            // Double-check after acquiring lock
            current = activeTargetId.get();
            if (current != null && !current.equals(targetId)) {
                RemoteError err = RemoteError.activeTargetExists(current);
                lastError = err;
                throw new IOException(err.safeMessage());
            }

            if (current != null && current.equals(targetId) && state == RemoteConnectionState.READY) {
                var snap = session != null ? session.attestationSnapshot() : null;
                if (snap != null) return snap;
            }

            // Load target — try v2 first, then v1
            RemoteTargetDescriptor descriptor;
            RemoteSshConnectionSpec connectionSpec;

            var v2Reg = store.getRegistration(targetId);
            if (v2Reg.isPresent()) {
                descriptor = RemoteTargetResolver.resolveDescriptor(v2Reg.get());
                connectionSpec = RemoteTargetResolver.resolveConnectionSpec(v2Reg.get());
            } else {
                RemoteTargetConfig config = store.get(targetId)
                    .orElseThrow(() -> {
                        RemoteError err = RemoteError.targetNotFound(targetId);
                        return new IOException(err.safeMessage());
                    });
                descriptor = RemoteTargetResolver.resolveLegacyDescriptor(config);
                connectionSpec = RemoteTargetResolver.resolveLegacyConnectionSpec(config);
            }

            // Update state
            state = RemoteConnectionState.CONNECTING;
            updateSnapshot(null);

            // Create and start session
            long gen = generation.incrementAndGet();
            session = new RemoteMcpSession(descriptor, connectionSpec, clock);

            state = RemoteConnectionState.ATTESTING;
            updateSnapshot(null);

            session.start();

            // On success: mount tools
            mountRemoteTools(targetId, gen);

            state = RemoteConnectionState.READY;
            activeTargetId.set(targetId);
            store.markActive(targetId);
            lastError = null;
            updateSnapshot(session.attestationSnapshot());

            log.info("[remote-svc] connected to {} (gen={})", targetId, gen);
            return session.attestationSnapshot();

        } catch (IOException | RuntimeException e) {
            state = RemoteConnectionState.FAILED;
            if (session != null) {
                lastError = session.firstError();
                if (lastError == null) {
                    lastError = RemoteError.of("RMT-006",
                        e.getMessage() != null ? e.getMessage() : "unknown error", false);
                }
            } else {
                lastError = RemoteError.of("RMT-006",
                    e.getMessage() != null ? e.getMessage() : "unknown error", false);
            }
            updateSnapshot(null);
            cleanupAfterFailure();
            if (e instanceof IOException ioe) throw ioe;
            throw new IOException(e.getMessage(), e);
        } finally {
            connectLock.unlock();
        }
    }

    // ── Disconnect ────────────────────────────────────────────────────

    /** Disconnect the active target. Idempotent. */
    public void disconnect() {
        connectLock.lock();
        try {
            String targetId = activeTargetId.getAndSet(null);
            if (targetId == null && session == null) return;

            // Prevent new calls
            state = RemoteConnectionState.DISCONNECTED;
            updateSnapshot(null);

            // Unmount tools first (prevents new calls)
            unmountRemoteTools();

            // Close session
            if (session != null) {
                session.close();
                session = null;
            }

            if (targetId != null) {
                store.markInactive(targetId);
            }
            lastError = null;
            log.info("[remote-svc] disconnected");
        } finally {
            connectLock.unlock();
        }
    }

    // ── Tool access ───────────────────────────────────────────────────

    /** Get the underlying MCP session (for tool adapters). Only valid when READY. */
    public RemoteMcpSession getSession() {
        if (state != RemoteConnectionState.READY) return null;
        return session;
    }

    /** Check if a tool call with the given generation is still valid. */
    public boolean isGenerationValid(long toolGeneration) {
        return toolGeneration == generation.get() && state == RemoteConnectionState.READY;
    }

    // ── Close ─────────────────────────────────────────────────────────

    @Override
    public void close() {
        disconnect();
        state = RemoteConnectionState.CLOSED;
    }

    // ── Internal ──────────────────────────────────────────────────────

    private void mountRemoteTools(String targetId, long gen) {
        if (session == null || session.attestedTools().isEmpty()) return;

        String ownerId = "remote:" + targetId;
        String sanitized = targetId.replace('-', '_');
        String prefix = "mcp__remote_" + sanitized + "__";

        List<Tool> tools = new ArrayList<>();
        for (McpToolDef toolDef : session.attestedTools()) {
            String fullName = prefix + toolDef.name();
            String safeSchema = toolDef.inputSchema() != null
                ? toolDef.inputSchema().toString() : "{}";

            var adapter = new TargetBoundToolAdapter(
                fullName, toolDef, session, gen, this,
                "[MCP:remote:" + targetId + "] " + toolDef.description(),
                safeSchema);
            tools.add(adapter);
        }

        toolMount = registry.mount(ownerId, tools);
        log.info("[remote-svc] mounted {} tools: {}", tools.size(),
            tools.stream().map(Tool::name).toList());
    }

    private void unmountRemoteTools() {
        if (toolMount != null) {
            toolMount.close();
            toolMount = null;
        }
    }

    private void cleanupAfterFailure() {
        unmountRemoteTools();
        if (session != null) {
            session.close();
            session = null;
        }
        activeTargetId.set(null);
    }

    private void updateSnapshot(RemoteAttestationSnapshot attestation) {
        String tid = activeTargetId.get();
        // Use the descriptor's targetId or a placeholder during connection setup
        String displayId = tid != null ? tid : "<connecting>";
        List<String> toolNames = toolMount != null ? toolMount.toolNames() : List.of();
        lastSnapshot = new RemoteConnectionSnapshot(
            displayId,
            state,
            generation.get(),
            attestation,
            toolNames,
            lastError,
            clock.instant()
        );
    }
}
