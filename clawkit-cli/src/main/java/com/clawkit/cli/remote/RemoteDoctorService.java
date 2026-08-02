package com.clawkit.cli.remote;

import com.clawkit.tools.remote.RemoteMcpSession;
import com.clawkit.tools.remote.RemoteAttestationSnapshot;
import com.clawkit.tools.remote.RemoteError;
import com.clawkit.tools.remote.RemoteSshConnectionSpec;
import com.clawkit.tools.remote.RemoteTargetDescriptor;
import com.clawkit.tools.remote.RemoteSshSafetyPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic remote doctor — checks SSH availability, config safety,
 * host identity, agent, transport, MCP handshake, and attestation.
 *
 * <p>Does NOT call LLM, does NOT call tools/call, does NOT mount ToolRegistry.
 * Uses a temporary {@link RemoteMcpSession} probe only.
 *
 * <p>Design: PRODUCT-1 §9.
 */
public class RemoteDoctorService {

    private final RemoteTargetStore store;
    private final SystemOpenSshFacade sshFacade;
    private final Clock clock;
    private final ProbeSessionFactory probeSessionFactory;

    public RemoteDoctorService(RemoteTargetStore store, SystemOpenSshFacade sshFacade) {
        this(store, sshFacade, Clock.systemUTC());
    }

    public RemoteDoctorService(RemoteTargetStore store, SystemOpenSshFacade sshFacade,
                                Clock clock) {
        this(store, sshFacade, clock, DefaultProbeSession::new);
    }

    RemoteDoctorService(RemoteTargetStore store, SystemOpenSshFacade sshFacade,
                        Clock clock, ProbeSessionFactory probeSessionFactory) {
        this.store = store;
        this.sshFacade = sshFacade;
        this.clock = clock;
        this.probeSessionFactory = probeSessionFactory;
    }

    /**
     * Run a full doctor check.
     */
    public RemoteDoctorReport check(String targetId) {
        Instant startedAt = clock.instant();
        List<RemoteDoctorReport.DoctorCheck> checks = new ArrayList<>();
        boolean failed = false;

        // Resolve target
        RemoteTargetDescriptor descriptor = null;
        RemoteSshConnectionSpec connectionSpec = null;
        boolean isAliasMode = false;

        var v2Reg = store.getRegistration(targetId);
        if (v2Reg.isPresent()) {
            descriptor = RemoteTargetResolver.resolveDescriptor(v2Reg.get());
            connectionSpec = RemoteTargetResolver.resolveConnectionSpec(v2Reg.get());
            isAliasMode = v2Reg.get().connection() instanceof OpenSshAliasReference;
        } else {
            var v1cfg = store.get(targetId);
            if (v1cfg.isPresent()) {
                descriptor = RemoteTargetResolver.resolveLegacyDescriptor(v1cfg.get());
                connectionSpec = RemoteTargetResolver.resolveLegacyConnectionSpec(v1cfg.get());
            }
        }

        if (descriptor == null) {
            checks.add(fail("TARGET_NOT_FOUND",
                "Target not registered: " + targetId,
                "Register with: /remote add --from-ssh <alias>"));
            skipAllAfter(checks, RemoteDoctorReport.DoctorStage.OPENSSH);
            return buildReport(targetId, checks, startedAt);
        }

        // ── Stage 1: OPENSSH ─────────────────────────────────────────
        failed |= stageOpenSsh(checks, failed);

        // ── Stage 2: SSH_CONFIG ──────────────────────────────────────
        failed |= stageSshConfig(checks, failed, targetId, isAliasMode);

        // ── Stage 3: SSH_SAFETY ──────────────────────────────────────
        failed |= stageSshSafety(checks, failed, connectionSpec);

        // ── Stage 4–8: Probe-based (HOST_IDENTITY through ATTESTATION) ─
        CleanupResult cleanup;
        if (!failed) {
            cleanup = probeRemoteStages(checks, descriptor, connectionSpec, targetId);
        } else {
            skipRemaining(checks, "Previous check failed");
            cleanup = CleanupResult.noSession();
        }

        // ── Stage 9: CLEANUP ─────────────────────────────────────────
        stageCleanup(checks, cleanup);

        return buildReport(targetId, checks, startedAt);
    }

    // ── Stage 1: OPENSSH ──────────────────────────────────────────────

    private boolean stageOpenSsh(List<RemoteDoctorReport.DoctorCheck> checks,
                                  boolean skip) {
        Instant start = clock.instant();
        if (skip) {
            checks.add(skipped(RemoteDoctorReport.DoctorStage.OPENSSH,
                "Previous check failed", start));
            return true;
        }
        try {
            var v = sshFacade.checkVersion();
            if (v.available()) {
                checks.add(pass(RemoteDoctorReport.DoctorStage.OPENSSH,
                    "SSH_AVAILABLE", "OpenSSH available: " + v.version(),
                    start));
                return false;
            }
            checks.add(fail(RemoteDoctorReport.DoctorStage.OPENSSH,
                "SSH_NOT_FOUND",
                "OpenSSH (ssh) not found or not working",
                "Install OpenSSH: https://www.openssh.com", start));
            return true;
        } catch (Exception e) {
            checks.add(fail(RemoteDoctorReport.DoctorStage.OPENSSH,
                "SSH_ERROR", "Cannot check OpenSSH: " + e.getMessage(),
                "Verify ssh is on PATH", start));
            return true;
        }
    }

    // ── Stage 2: SSH_CONFIG ───────────────────────────────────────────

    private boolean stageSshConfig(List<RemoteDoctorReport.DoctorCheck> checks,
                                    boolean skip, String targetId, boolean isAliasMode) {
        Instant start = clock.instant();
        if (skip) {
            checks.add(skipped(RemoteDoctorReport.DoctorStage.SSH_CONFIG,
                "Previous check failed", start));
            return true;
        }
        if (!isAliasMode) {
            checks.add(pass(RemoteDoctorReport.DoctorStage.SSH_CONFIG,
                "EXPLICIT_ENDPOINT",
                "Explicit endpoint mode — no SSH config audit needed", start));
            return false;
        }
        var disc = new SshTargetDiscovery(sshFacade);
        var result = disc.discover();
        if (!result.safe()) {
            checks.add(fail(RemoteDoctorReport.DoctorStage.SSH_CONFIG,
                "SSH_CONFIG_UNSAFE",
                "SSH config unsafe: " + String.join("; ", result.unsafeReasons()),
                "Create a clean SSH config with only HostName/User/ProxyJump", start));
            return true;
        }
        checks.add(pass(RemoteDoctorReport.DoctorStage.SSH_CONFIG,
            "SSH_CONFIG_SAFE", "SSH config audit passed, "
                + result.aliases().size() + " alias(es) found", start));
        return false;
    }

    // ── Stage 3: SSH_SAFETY ───────────────────────────────────────────

    private boolean stageSshSafety(List<RemoteDoctorReport.DoctorCheck> checks,
                                    boolean skip, RemoteSshConnectionSpec spec) {
        Instant start = clock.instant();
        if (skip) {
            checks.add(skipped(RemoteDoctorReport.DoctorStage.SSH_SAFETY,
                "Previous check failed", start));
            return true;
        }
        var args = spec.sshArgs();
        boolean safe = args.contains("BatchMode=yes")
            && args.contains("PasswordAuthentication=no")
            && args.contains("StrictHostKeyChecking=yes")
            && args.contains("-T")
            && !args.contains("-o PasswordAuthentication=yes"); // negative check
        if (safe) {
            checks.add(pass(RemoteDoctorReport.DoctorStage.SSH_SAFETY,
                "SAFETY_OK", "SSH safety: BatchMode, no password, strict host key, no TTY",
                start));
            return false;
        }
        checks.add(fail(RemoteDoctorReport.DoctorStage.SSH_SAFETY,
            "SAFETY_FAIL", "SSH safety parameters incomplete",
            "Internal error — reinstall Clawkit", start));
        return true;
    }

    // ── Probe stages (4–8): HOST_IDENTITY through ATTESTATION ─────────

    private CleanupResult probeRemoteStages(List<RemoteDoctorReport.DoctorCheck> checks,
                                    RemoteTargetDescriptor descriptor,
                                    RemoteSshConnectionSpec spec, String targetId) {
        // Stage 4: SSH_AGENT (readiness check, does NOT block)
        stageSshAgent(checks);

        // Stage 5–8: SSH transport + MCP + attestation via temp session
        ProbeSession session = null;
        Instant transportStart = clock.instant();
        try {
            session = probeSessionFactory.create(descriptor, spec, clock);
            session.start();

            // SSH_TRANSPORT: passed (session.start() succeeded)
            checks.add(pass(RemoteDoctorReport.DoctorStage.SSH_TRANSPORT,
                "TRANSPORT_OK", "SSH transport established", transportStart));

            // REMOTE_MCP: initialized
            checks.add(pass(RemoteDoctorReport.DoctorStage.REMOTE_MCP,
                "MCP_READY", "Remote MCP server responded", transportStart));

            // ATTESTATION
            var att = session.attestationSnapshot();
            if (att != null) {
                checks.add(pass(RemoteDoctorReport.DoctorStage.ATTESTATION,
                    "ATTEST_PASS",
                    "Attestation: " + att.serverName() + " / "
                        + att.capabilityProfile() + " / "
                        + att.toolNames().size() + " tools matched",
                    transportStart));
            } else {
                checks.add(fail(RemoteDoctorReport.DoctorStage.ATTESTATION,
                    "RMT-007_MCP_FAILURE", "Remote MCP did not produce attestation",
                    "Verify the remote MCP server and retry /remote doctor " + targetId,
                    transportStart));
            }

            // HOST_IDENTITY: TRUSTED (handshake succeeded with StrictHostKeyChecking=yes)
            checks.add(pass(RemoteDoctorReport.DoctorStage.HOST_IDENTITY,
                "HOST_TRUSTED",
                "Host key trusted (StrictHostKeyChecking=yes passed)",
                transportStart));

        } catch (IOException e) {
            // Transport or attestation failed — classify the error
            String msg = e.getMessage() != null ? e.getMessage() : "";
            String code = classifyProbeError(session != null ? session.firstError() : null, msg);

            // SSH_TRANSPORT
            if (isTransportError(code)) {
                checks.add(fail(RemoteDoctorReport.DoctorStage.SSH_TRANSPORT,
                    code, "SSH transport failed: " + msg,
                    transportNextAction(code, spec), transportStart));
                checks.add(skipped(RemoteDoctorReport.DoctorStage.REMOTE_MCP,
                    "Transport failed", transportStart));
                checks.add(skipped(RemoteDoctorReport.DoctorStage.ATTESTATION,
                    "Transport failed", transportStart));

                // HOST_IDENTITY classification
                if (code.contains("HOST_KEY")) {
                    checks.add(fail(RemoteDoctorReport.DoctorStage.HOST_IDENTITY,
                        code, "Host key issue: " + msg,
                        hostKeyNextAction(code), transportStart));
                } else {
                    checks.add(skipped(RemoteDoctorReport.DoctorStage.HOST_IDENTITY,
                        "Transport failed — host identity not checked", transportStart));
                }
            } else {
                // Transport OK but MCP/Attestation failed
                checks.add(pass(RemoteDoctorReport.DoctorStage.SSH_TRANSPORT,
                    "TRANSPORT_OK", "SSH transport established", transportStart));
                checks.add(fail(RemoteDoctorReport.DoctorStage.REMOTE_MCP,
                    code, "MCP/Attestation failed: " + msg,
                    "1. Verify remote opsro MCP is deployed\n"
                        + "2. Check /remote doctor " + targetId + " --verbose",
                    transportStart));
                checks.add(skipped(RemoteDoctorReport.DoctorStage.ATTESTATION,
                    "MCP failed — attestation skipped", transportStart));
                checks.add(pass(RemoteDoctorReport.DoctorStage.HOST_IDENTITY,
                    "HOST_TRUSTED", "SSH transport OK, host identity assumed trusted",
                    transportStart));
            }
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (RuntimeException closeError) {
                    return CleanupResult.failed(closeError.getClass().getSimpleName());
                }
                return session.isClosed()
                    ? CleanupResult.successfullyClosed()
                    : CleanupResult.failed("probe session did not reach CLOSED");
            }
        }
        return CleanupResult.noSession();
    }

    // ── Stage 5: SSH_AGENT ────────────────────────────────────────────

    private void stageSshAgent(List<RemoteDoctorReport.DoctorCheck> checks) {
        Instant start = clock.instant();
        String sock = System.getenv("SSH_AUTH_SOCK");
        if (sock != null && !sock.isEmpty()) {
            checks.add(pass(RemoteDoctorReport.DoctorStage.SSH_AGENT,
                "AGENT_AVAILABLE",
                "SSH Agent detected (readiness indicator only)",
                start));
        } else {
            checks.add(new RemoteDoctorReport.DoctorCheck(
                RemoteDoctorReport.DoctorStage.SSH_AGENT,
                RemoteDoctorReport.DoctorCheckStatus.WARN,
                "AGENT_NOT_DETECTED",
                "SSH Agent not detected — key with passphrase may require manual ssh-add",
                "Run: ssh-add -l to check. Add key: ssh-add <keyfile>",
                Map.of(), Duration.between(start, clock.instant())));
        }
    }

    // ── Stage 9: CLEANUP ──────────────────────────────────────────────

    private void stageCleanup(List<RemoteDoctorReport.DoctorCheck> checks, CleanupResult result) {
        Instant start = clock.instant();
        if (result.closed()) {
            checks.add(pass(RemoteDoctorReport.DoctorStage.CLEANUP,
                "CLEANUP_OK", result.summary(), start));
        } else {
            checks.add(fail(RemoteDoctorReport.DoctorStage.CLEANUP,
                "CLEANUP_FAILED", result.summary(),
                "Restart Clawkit before retrying the remote connection", start));
        }
    }

    // ── Error classification ─────────────────────────────────────────

    static String classifySshError(String message) {
        if (message == null) return "SSH_FAILED";
        String m = message.toLowerCase();
        if (m.contains("host key verification failed")
            || (m.contains("host key") && m.contains("unknown")))
            return "RMT-004_HOST_KEY_UNKNOWN";
        if ((m.contains("host key") || m.contains("host identification"))
            && (m.contains("changed") || m.contains("mismatch")
                || m.contains("offending")))
            return "RMT-004_HOST_KEY_CHANGED";
        if (m.contains("permission denied") || m.contains("publickey")
            || (m.contains("authentication") && m.contains("fail")))
            return "RMT-005_AUTH_FAILED";
        if (m.contains("connection refused") || m.contains("timed out")
            || m.contains("no route to host") || m.contains("could not resolve")
            || m.contains("name or service not known"))
            return "RMT-006_UNREACHABLE";
        if (m.contains("protocol") || m.contains("mcp") || m.contains("initialize"))
            return "RMT-007_MCP_FAILURE";
        return "SSH_FAILED";
    }

    static String classifyProbeError(RemoteError error, String fallbackMessage) {
        if (error == null) return classifySshError(fallbackMessage);
        return switch (error.code()) {
            case "RMT-004" -> "changed".equals(error.safeDetails().get("hostKeyStatus"))
                ? "RMT-004_HOST_KEY_CHANGED" : "RMT-004_HOST_KEY_UNKNOWN";
            case "RMT-005" -> "RMT-005_AUTH_FAILED";
            case "RMT-006" -> "RMT-006_UNREACHABLE";
            default -> error.code().startsWith("RMT-007") || error.code().startsWith("RMT-008")
                || error.code().startsWith("RMT-009") || error.code().startsWith("RMT-010")
                || error.code().startsWith("RMT-011")
                ? "RMT-007_MCP_FAILURE" : classifySshError(fallbackMessage);
        };
    }

    private static boolean isTransportError(String code) {
        return code.contains("HOST_KEY") || code.contains("AUTH")
            || code.contains("UNREACHABLE") || code.equals("SSH_FAILED");
    }

    private static String transportNextAction(String code, RemoteSshConnectionSpec spec) {
        if (code.contains("HOST_KEY_UNKNOWN")) {
            return "1. Verify the host fingerprint manually\n"
                + "2. Run: ssh <alias> to accept the host key interactively\n"
                + "3. Then re-run: /remote doctor <target>";
        }
        if (code.contains("HOST_KEY_CHANGED")) {
            return "WARNING: Host key changed — possible MITM attack\n"
                + "1. Verify the new key through a trusted channel\n"
                + "2. If legitimate, remove the old key from known_hosts\n"
                + "3. Re-run /remote doctor <target>";
        }
        if (code.contains("AUTH")) {
            return "1. Run: ssh <alias> to verify SSH connectivity\n"
                + "2. If key has passphrase: ssh-add <keyfile>\n"
                + "3. Check that the remote user has your public key in authorized_keys";
        }
        return "1. Verify network connectivity to the remote host\n"
            + "2. Check that SSH service is running on the remote host\n"
            + "3. Run /remote doctor <target> --verbose for details";
    }

    private static String hostKeyNextAction(String code) {
        if (code.contains("UNKNOWN")) {
            return "The host key is not in known_hosts.\n"
                + "Run: ssh <alias> once to accept and store the host key.";
        }
        return "The host key has changed — possible security issue.\n"
            + "Verify the new key before proceeding.";
    }

    // ── Check builders ────────────────────────────────────────────────

    private RemoteDoctorReport.DoctorCheck pass(
            RemoteDoctorReport.DoctorStage stage, String code,
            String summary, Instant start) {
        return new RemoteDoctorReport.DoctorCheck(stage,
            RemoteDoctorReport.DoctorCheckStatus.PASS, code, summary, null, Map.of(),
            Duration.between(start, clock.instant()));
    }

    private RemoteDoctorReport.DoctorCheck fail(
            String code, String summary, String nextAction) {
        return new RemoteDoctorReport.DoctorCheck(
            RemoteDoctorReport.DoctorStage.OPENSSH,
            RemoteDoctorReport.DoctorCheckStatus.FAIL, code, summary, nextAction,
            Map.of(), Duration.ZERO);
    }

    private RemoteDoctorReport.DoctorCheck fail(
            RemoteDoctorReport.DoctorStage stage, String code,
            String summary, String nextAction, Instant start) {
        return new RemoteDoctorReport.DoctorCheck(stage,
            RemoteDoctorReport.DoctorCheckStatus.FAIL, code, summary, nextAction, Map.of(),
            Duration.between(start, clock.instant()));
    }

    private RemoteDoctorReport.DoctorCheck skipped(
            RemoteDoctorReport.DoctorStage stage, String reason, Instant start) {
        return new RemoteDoctorReport.DoctorCheck(stage,
            RemoteDoctorReport.DoctorCheckStatus.SKIPPED, "SKIPPED", reason, null, Map.of(),
            Duration.between(start, clock.instant()));
    }

    private void skipAllAfter(List<RemoteDoctorReport.DoctorCheck> checks,
                               RemoteDoctorReport.DoctorStage from) {
        Instant now = clock.instant();
        boolean skip = true;
        for (var stage : RemoteDoctorReport.DoctorStage.values()) {
            if (stage == from) skip = true;
            if (skip && stage != from) {
                // Only skip stages that come after 'from'
            }
            if (stage.ordinal() >= from.ordinal() && stage != from) {
                checks.add(skipped(stage, "Target not registered", now));
            }
        }
    }

    private void skipRemaining(List<RemoteDoctorReport.DoctorCheck> checks, String reason) {
        Instant now = clock.instant();
        for (var stage : RemoteDoctorReport.DoctorStage.values()) {
            boolean alreadyReported = checks.stream().anyMatch(c -> c.stage() == stage);
            if (!alreadyReported && stage != RemoteDoctorReport.DoctorStage.CLEANUP) {
                checks.add(skipped(stage, reason, now));
            }
        }
    }

    private RemoteDoctorReport buildReport(String targetId,
            List<RemoteDoctorReport.DoctorCheck> checks, Instant startedAt) {
        var ordered = checks.stream().sorted(java.util.Comparator.comparingInt(
            c -> c.stage().ordinal())).toList();
        boolean anyFail = ordered.stream()
            .anyMatch(c -> c.status() == RemoteDoctorReport.DoctorCheckStatus.FAIL);
        boolean anyWarn = ordered.stream()
            .anyMatch(c -> c.status() == RemoteDoctorReport.DoctorCheckStatus.WARN);
        var overall = anyFail ? RemoteDoctorReport.DoctorOverallStatus.FAILED
            : anyWarn ? RemoteDoctorReport.DoctorOverallStatus.DEGRADED
            : RemoteDoctorReport.DoctorOverallStatus.READY;
        return new RemoteDoctorReport(targetId, overall, ordered,
            startedAt, clock.instant());
    }

    interface ProbeSessionFactory {
        ProbeSession create(RemoteTargetDescriptor target, RemoteSshConnectionSpec spec, Clock clock);
    }

    interface ProbeSession extends AutoCloseable {
        void start() throws IOException;
        RemoteAttestationSnapshot attestationSnapshot();
        RemoteError firstError();
        boolean isClosed();
        @Override void close();
    }

    private static final class DefaultProbeSession implements ProbeSession {
        private final RemoteMcpSession delegate;

        private DefaultProbeSession(RemoteTargetDescriptor target, RemoteSshConnectionSpec spec,
                                    Clock clock) {
            this.delegate = new RemoteMcpSession(target, spec, clock);
        }

        @Override public void start() throws IOException { delegate.start(); }
        @Override public RemoteAttestationSnapshot attestationSnapshot() {
            return delegate.attestationSnapshot();
        }
        @Override public RemoteError firstError() { return delegate.firstError(); }
        @Override public boolean isClosed() {
            return delegate.internalState() == RemoteMcpSession.InternalState.CLOSED;
        }
        @Override public void close() { delegate.close(); }
    }

    private record CleanupResult(boolean closed, String summary) {
        static CleanupResult successfullyClosed() {
            return new CleanupResult(true, "Probe session closed");
        }
        static CleanupResult noSession() { return new CleanupResult(true, "No probe session was created"); }
        static CleanupResult failed(String detail) {
            return new CleanupResult(false, "Probe cleanup failed: " + detail);
        }
    }

    // ── Renderers ─────────────────────────────────────────────────────

    public static String renderText(RemoteDoctorReport report, boolean verbose) {
        StringBuilder sb = new StringBuilder();
        String icon = report.status() == RemoteDoctorReport.DoctorOverallStatus.READY
            ? "READY" : "FAILED";
        sb.append("  [").append(report.targetId()).append("]  ").append(icon).append("\n\n");

        for (var check : report.checks()) {
            String ci = switch (check.status()) {
                case PASS -> "✓";
                case WARN -> "⚠";
                case FAIL -> "✗";
                case SKIPPED -> "⧸";
            };
            sb.append("  ").append(ci).append(" ").append(check.stage())
                .append("  ").append(check.summary()).append("\n");
            if (check.status() == RemoteDoctorReport.DoctorCheckStatus.FAIL
                && check.nextAction() != null) {
                sb.append("    Next: ").append(check.nextAction()).append("\n");
            }
            if (verbose && check.duration().toMillis() > 0) {
                sb.append("    (").append(check.duration().toMillis()).append("ms)\n");
            }
        }
        return sb.toString();
    }

    public static String renderJson(RemoteDoctorReport report) {
        try {
            var mapper = new ObjectMapper()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(SerializationFeature.INDENT_OUTPUT);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("targetId", report.targetId());
            map.put("status", report.status().name());
            map.put("startedAt", report.startedAt().toString());
            map.put("completedAt", report.completedAt().toString());
            List<Map<String, Object>> cl = new ArrayList<>();
            for (var c : report.checks()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("stage", c.stage().name());
                cm.put("status", c.status().name());
                cm.put("code", c.code());
                cm.put("summary", c.summary());
                if (c.nextAction() != null) cm.put("nextAction", c.nextAction());
                cm.put("durationMs", c.duration().toMillis());
                cl.add(cm);
            }
            map.put("checks", cl);
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }
}
