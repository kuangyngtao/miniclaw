package com.clawkit.ops.loop.repair;

import com.clawkit.ops.loop.DeadlineControl;
import com.clawkit.ops.loop.RemoteOpsError;
import com.clawkit.ops.loop.RemoteTargetDescriptor;
import com.clawkit.ops.loop.SshConnectionConfig;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.mcp.McpCallResult;
import com.clawkit.tools.mcp.McpClient;
import com.clawkit.tools.mcp.McpInitializeResult;
import com.clawkit.tools.mcp.McpToolDef;
import com.clawkit.tools.mcp.McpTransport;
import com.clawkit.tools.mcp.StdioTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Restricted SSH session with the opsfix identity.
 *
 * <p>Follows the same lifecycle pattern as {@link com.clawkit.ops.loop.RemoteOpsSession}
 * but uses the opsfix SSH key, connects to the fix gateway, and only exposes
 * the {@code restart_service} tool.
 *
 * <p>State machine: NEW → STARTING → INITIALIZING → READY → DRAINING → CLOSED
 * (any state → FAILED).
 */
public final class OpsFixSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpsFixSession.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String FIX_SERVER_NAME = "clawkit-ops-fix";
    private static final String FIX_PROFILE = "FIX_ORDER_API_V1";

    public enum State { NEW, STARTING, INITIALIZING, READY, DRAINING, FAILED, CLOSED }

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
    private String expectedToolSetHash;

    public OpsFixSession(RemoteTargetDescriptor target, SshConnectionConfig connectionConfig) {
        this(target, connectionConfig, Clock.systemUTC());
    }

    public OpsFixSession(RemoteTargetDescriptor target, SshConnectionConfig connectionConfig, Clock clock) {
        this.target = target;
        this.connectionConfig = connectionConfig;
        this.clock = clock;
        this.requestTimeout = connectionConfig.requestTimeout();
        this.maxOutputBytes = connectionConfig.maxOutputBytes();
        // Pre-compute expected tool set hash (only restart_service in FIX_ORDER_API_V1)
        this.expectedToolSetHash = computeToolSetHashFromNames(Set.of("restart_service"));
    }

    public State state() { return state.get(); }
    public String targetId() { return target.targetId(); }
    public List<RemoteOpsError> errors() { return List.copyOf(errors); }
    public RemoteOpsError firstError() { return firstError; }

    /** Start the SSH transport and perform MCP handshake. */
    public void start() throws IOException {
        if (!state.compareAndSet(State.NEW, State.STARTING)) {
            throw new IllegalStateException("session already started: " + state.get());
        }
        startedAt = clock.instant();

        List<String> sshArgs = connectionConfig.sshArgs();
        log.info("[ops-fix:{}] starting ssh transport to {}", target.targetId(),
            connectionConfig.safeRef());

        transport = new StdioTransport("ssh", sshArgs, Map.of(), Path.of("."));
        try {
            transport.start();
        } catch (IOException e) {
            transitionToFailed(classifyError("transport", e));
            throw e;
        }

        state.set(State.INITIALIZING);
        client = new McpClient(transport, target.targetId());

        try {
            doInitialize();
        } catch (IOException e) {
            transitionToFailed(classifyError("initialize", e));
            closeTransport();
            throw e;
        }

        try {
            attestFixToolSet();
        } catch (IOException e) {
            transitionToFailed(classifyError("attest", e));
            closeTransport();
            throw e;
        }

        state.set(State.READY);
        log.info("[ops-fix:{}] ready ({}ms)", target.targetId(),
            Duration.between(startedAt, clock.instant()).toMillis());
    }

    /** Execute restart_service on the remote target. */
    public RepairResult executeRestart(String incidentId, String repairRunId) throws IOException {
        if (state.get() != State.READY) {
            throw new IOException("[ops-fix:" + target.targetId()
                + "] cannot execute: session is " + state.get());
        }

        Instant start = clock.instant();
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            ObjectNode args = mapper.createObjectNode();
            args.put("serviceId", "order-api");

            McpCallResult result = client.callTool("restart_service", args,
                new DeadlineControl(requestTimeout, clock.instant()));

            boolean success = !result.isError();
            String text = result.text() != null ? result.text() : "";

            return new RepairResult(
                incidentId,
                "fix-" + repairRunId,
                repairRunId,
                success ? com.clawkit.reliability.attempt.AttemptState.VERIFICATION_PENDING
                    : com.clawkit.reliability.attempt.AttemptState.FAILED_NO_EFFECT,
                success ? com.clawkit.tools.action.EffectCertainty.EFFECT_CONFIRMED
                    : com.clawkit.tools.action.EffectCertainty.NO_EFFECT_CONFIRMED,
                success ? com.clawkit.tools.action.FailureClass.NONE
                    : com.clawkit.tools.action.FailureClass.LOCAL_ERROR_NO_EFFECT,
                text,
                start,
                clock.instant(),
                null);
        } catch (IOException e) {
            RemoteOpsError err = classifyError("restart_service", e);
            errors.add(err);
            return new RepairResult(
                incidentId, "fix-" + repairRunId, repairRunId,
                com.clawkit.reliability.attempt.AttemptState.OUTCOME_UNKNOWN,
                com.clawkit.tools.action.EffectCertainty.EFFECT_UNKNOWN,
                com.clawkit.tools.action.FailureClass.TIMEOUT_OUTCOME_UNKNOWN,
                "SSH error: " + err.safeMessage(),
                start, clock.instant(), null);
        }
    }

    @Override
    public void close() {
        State current = state.get();
        if (current == State.CLOSED) return;
        state.set(State.DRAINING);
        closeTransport();
        state.set(State.CLOSED);
        long duration = startedAt != null
            ? Duration.between(startedAt, clock.instant()).toMillis() : 0;
        log.info("[ops-fix:{}] closed ({}ms), firstError={}",
            target.targetId(), duration, firstError != null ? firstError.code() : "none");
    }

    private void closeTransport() {
        if (transport != null) {
            try { transport.stop(); } catch (Exception ignored) { }
            try { transport.close(); } catch (Exception ignored) { }
        }
    }

    private void doInitialize() throws IOException {
        McpInitializeResult info = client.initialize(
            new DeadlineControl(requestTimeout, clock.instant()));

        if (!PROTOCOL_VERSION.equals(info.protocolVersion())) {
            throw new IOException("protocol version mismatch: expected "
                + PROTOCOL_VERSION + " but got " + info.protocolVersion());
        }
        if (!FIX_SERVER_NAME.equals(info.serverName())) {
            throw new IOException("server name mismatch: expected "
                + FIX_SERVER_NAME + " but got " + info.serverName());
        }
        String profile = info.serverInfoText("capabilityProfile");
        if (!FIX_PROFILE.equals(profile)) {
            throw new IOException("capabilityProfile mismatch: expected "
                + FIX_PROFILE + " but got " + profile);
        }

        log.info("[ops-fix:{}] attestation: protocol={}, server={}, profile={}",
            target.targetId(), info.protocolVersion(), info.serverName(), profile);
    }

    private void attestFixToolSet() throws IOException {
        List<McpToolDef> tools = client.listTools();
        Set<String> names = tools.stream().map(McpToolDef::name).collect(Collectors.toSet());

        if (names.size() != 1 || !names.contains("restart_service")) {
            throw new IOException("fix tool set mismatch: expected [restart_service], got " + names);
        }

        String listHash = computeToolSetHashFromNames(names);
        if (!expectedToolSetHash.equals(listHash)) {
            throw new IOException("fix tools/list hash mismatch: expected "
                + expectedToolSetHash + " but got " + listHash);
        }

        // Verify the restart_service tool has destructiveHint (it's a write operation)
        for (McpToolDef tool : tools) {
            JsonNode a = tool.annotations();
            if (a == null || !a.path("destructiveHint").asBoolean(true)) {
                throw new IOException("restart_service tool must have destructiveHint=true");
            }
        }
    }

    private RemoteOpsError classifyError(String context, IOException e) {
        return RemoteOpsError.sshTransportClosed(target.targetId(), clock.instant(),
            Duration.between(startedAt != null ? startedAt : clock.instant(), clock.instant()).toMillis(), -1);
    }

    private void transitionToFailed(RemoteOpsError error) {
        state.set(State.FAILED);
        if (firstError == null) firstError = error;
        errors.add(error);
    }

    static String computeToolSetHashFromNames(Set<String> names) {
        String[] sorted = names.toArray(String[]::new);
        java.util.Arrays.sort(sorted);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String name : sorted) {
                md.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            byte[] digest = md.digest();
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
