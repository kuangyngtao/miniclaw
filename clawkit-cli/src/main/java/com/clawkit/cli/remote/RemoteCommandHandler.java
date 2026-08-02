package com.clawkit.cli.remote;

import com.clawkit.cli.ConsoleRenderer;
import com.clawkit.tools.ToolMount;
import com.clawkit.tools.ToolRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@code /remote} slash commands using deterministic parsing.
 *
 * <p>All operations are deterministic — no LLM involvement.
 * Uses {@link RemoteCommandParser} for tokenization.
 */
public class RemoteCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(RemoteCommandHandler.class);

    private final RemoteConnectionService service;
    private final RemoteTargetStore store;
    private final ToolRegistry registry;
    private final RemoteOnboardingService onboarding;
    private final RemoteDoctorService doctorService;

    public RemoteCommandHandler(RemoteConnectionService service, RemoteTargetStore store,
                                 ToolRegistry registry, RemoteOnboardingService onboarding,
                                 RemoteDoctorService doctorService) {
        this.service = service;
        this.store = store;
        this.registry = registry;
        this.onboarding = onboarding;
        this.doctorService = doctorService;
    }

    /** Handle a /remote command. Returns true if recognized. */
    public boolean handle(String arguments) {
        var cmd = RemoteCommandParser.parse(arguments);
        try {
            return switch (cmd.subCommand()) {
                case LIST -> { cmdList(); yield true; }
                case STATUS -> { cmdStatus(); yield true; }
                case INSPECT, SHOW -> { cmdInspect(cmd.arg(0)); yield true; }
                case ADD -> { cmdAdd(cmd); yield true; }
                case REMOVE -> { cmdRemove(cmd.arg(0)); yield true; }
                case CONNECT -> { cmdConnect(cmd.arg(0)); yield true; }
                case DISCONNECT -> { cmdDisconnect(); yield true; }
                case DOCTOR -> { cmdDoctor(cmd); yield true; }
                case TOOLS -> { cmdTools(); yield true; }
                case HELP -> { cmdHelp(); yield true; }
                case UNKNOWN -> { cmdHelp(); yield true; }
            };
        } catch (Exception e) {
            println("  [ERROR] " + e.getMessage());
            log.warn("[remote-cmd] failed: {}", e.getMessage());
            return true;
        }
    }

    // ── Sub-commands ──────────────────────────────────────────────────

    private void cmdStatus() {
        var snapshot = service.lastSnapshot();
        if (snapshot.isEmpty()) {
            println("  No active connection.");
            return;
        }
        var s = snapshot.get();
        String scope = "read-only"; // all current profiles are read-only
        println("  [" + s.targetId() + " · " + scope + "]");
        println("  state:     " + s.state());
        if (s.attestation() != null) {
            var a = s.attestation();
            println("  profile:   " + a.capabilityProfile());
            if (!s.mountedTools().isEmpty()) {
                println("  tools (" + s.mountedTools().size() + "): "
                    + String.join(", ", s.mountedTools()));
            }
            println("  latency:   connect=" + a.connectLatencyMs()
                + "ms attest=" + a.attestationLatencyMs() + "ms");
        }
        if (s.error() != null) {
            println("  error:     " + s.error().code() + " — " + s.error().safeMessage());
        }
    }

    private void cmdList() {
        List<String> ids = store.list();
        if (ids.isEmpty()) {
            println("  No registered targets.");
            println("  Use /remote add --from-ssh <alias> to register.");
            return;
        }
        println("  Registered targets:");
        for (String id : ids) {
            String active = store.isActive(id) ? " [ACTIVE]" : "";
            // Determine profile from either v1 or v2
            String profile = "?";
            var v1 = store.get(id);
            if (v1.isPresent()) {
                profile = v1.get().attestation().expectedCapabilityProfile();
            } else {
                var v2 = store.getRegistration(id);
                if (v2.isPresent()) {
                    var m = RemoteProfileCatalog.lookup(v2.get().profileManifestId());
                    profile = m.map(RemoteProfileManifest::capabilityProfile).orElse("?");
                }
            }
            println("    - " + id + active + "  " + profile);
        }
    }

    private void cmdInspect(String targetId) {
        if (targetId.isEmpty()) {
            // Show current connection details
            var snapshot = service.lastSnapshot();
            if (snapshot.isEmpty()) {
                println("  No active connection. Specify a targetId or connect first.");
                return;
            }
            var s = snapshot.get();
            println("  target:       " + s.targetId());
            println("  state:        " + s.state());
            println("  generation:   " + s.generation());
            if (s.attestation() != null) {
                var a = s.attestation();
                println("  server:       " + a.serverName());
                println("  protocol:     " + a.protocolVersion());
                println("  probe:        v" + a.probeVersion());
                println("  profile:      " + a.capabilityProfile());
                println("  toolSetHash:  " + a.advertisedToolSetHash());
                println("  contractHash: " + a.computedToolContractHash());
                println("  connect:      " + a.connectLatencyMs() + "ms");
                println("  attest:       " + a.attestationLatencyMs() + "ms");
            }
            return;
        }
        // Show stored target details
        var v1 = store.get(targetId);
        if (v1.isPresent()) {
            var c = v1.get();
            println("  targetId:    " + c.targetId());
            println("  schema:      v" + c.schemaVersion());
            println("  host:        " + c.endpoint().host() + ":" + c.endpoint().port());
            println("  user:        " + c.endpoint().user());
            println("  profile:     " + c.attestation().expectedCapabilityProfile());
            println("  server:      " + c.attestation().expectedServerName());
            println("  active:      " + store.isActive(targetId));
            return;
        }
        var v2 = store.getRegistration(targetId);
        if (v2.isPresent()) {
            var r = v2.get();
            println("  targetId:    " + r.targetId());
            println("  schema:      v" + r.schemaVersion());
            println("  connection:  " + r.connection().type());
            switch (r.connection()) {
                case OpenSshAliasReference a ->
                    println("  alias:       " + a.alias() + " (user=" + a.remoteUser() + ")");
                case LegacyExplicitEndpointReference ep ->
                    println("  host:        " + ep.host() + ":" + ep.port());
            }
            var m = RemoteProfileCatalog.lookup(r.profileManifestId());
            println("  profile:     " + m.map(RemoteProfileManifest::capabilityProfile).orElse("?"));
            println("  active:      " + store.isActive(targetId));
            return;
        }
        println("  Target not found: " + targetId);
    }

    private void cmdAdd(RemoteCommandParser.ParsedCommand cmd) {
        String fromSsh = cmd.option("from-ssh");
        String asId = cmd.option("as");
        String configFile = cmd.option("config");
        boolean replace = cmd.hasOption("replace");

        if (fromSsh != null && !fromSsh.isEmpty()) {
            cmdAddFromSsh(fromSsh, asId, replace);
            return;
        }

        if (configFile != null && !configFile.isEmpty()) {
            String targetId = cmd.arg(0);
            cmdAddLegacy(targetId, configFile, replace);
            return;
        }

        // Interactive discovery
        cmdAddInteractive();
    }

    private void cmdAddFromSsh(String alias, String asId, boolean replace) {
        try {
            var preview = onboarding.preview(alias, asId, null);
            println("  Preview for: " + preview.targetId());
            println("  Alias:       " + alias);
            println("  Hostname:    " + preview.sshGResult().hostname()
                + ":" + preview.sshGResult().port());
            println("  User:        " + preview.sshGResult().user());
            if (preview.sshGResult().hasProxyJump()) {
                println("  ProxyJump:   " + preview.sshGResult().proxyJump());
            }
            println("  Profile:     " + preview.profileManifest().displayName()
                + " (" + preview.profileManifest().capabilityProfile() + ")");
            println("  Access:      read-only");
            println("  Key files:   " + preview.sshGResult().identityFileCount());
            println("  SSH Agent:   "
                + (preview.sshGResult().agentEnabled() ? "detected" : "not detected"));

            onboarding.register(alias, preview.targetId(),
                preview.profileManifest().manifestId(), replace);
            println("  Registered:  " + preview.targetId());
            println("  Next: /remote doctor " + preview.targetId());
        } catch (IOException e) {
            println("  Discovery failed: " + e.getMessage());
            log.warn("[remote-cmd] add --from-ssh {} failed: {}", alias, e.getMessage());
        }
    }

    private void cmdAddLegacy(String targetId, String configFile, boolean replace) {
        if (targetId.isEmpty()) {
            println("  Usage: /remote add <targetId> --config <file> [--replace]");
            return;
        }
        Path configPath = Path.of(configFile);
        store.add(targetId, configPath, replace);
        println("  Target registered: " + targetId);
    }

    private void cmdAddInteractive() {
        var discovery = onboarding.discoverTargets();
        if (!discovery.safe()) {
            println("  SSH config contains unsafe directives:");
            for (String reason : discovery.unsafeReasons()) {
                println("    - " + reason);
            }
            println("  Cannot use interactive discovery with unsafe config.");
            println("  Use /remote add <id> --config <file> for explicit endpoint mode.");
            return;
        }
        if (discovery.aliases().isEmpty()) {
            println("  No explicit Host aliases found in SSH config.");
            println("  Use /remote add <id> --config <file> for explicit endpoint mode.");
            return;
        }
        println("  Found " + discovery.aliases().size() + " SSH target(s):");
        int i = 1;
        for (String alias : discovery.aliases()) {
            String source = discovery.sources().getOrDefault(alias, "?");
            println("    " + i + ". " + alias + "  (" + source + ")");
            i++;
        }
        println("  Use /remote add --from-ssh <alias> to register.");
    }

    private void cmdRemove(String targetId) {
        if (targetId.isEmpty()) {
            println("  Usage: /remote remove <targetId>");
            return;
        }
        store.remove(targetId);
        println("  Target removed: " + targetId);
    }

    private void cmdConnect(String targetId) {
        if (targetId.isEmpty()) {
            println("  Usage: /remote connect <targetId>");
            return;
        }
        if (!store.exists(targetId)) {
            println("  Target not found: " + targetId);
            return;
        }
        println("  Connecting to " + targetId + "...");
        try {
            var attestation = service.connect(targetId);
            println("  [" + targetId + " · read-only] READY");
            println("  Profile: " + attestation.capabilityProfile());
            println("  Tools: " + attestation.toolNames().size() + " — "
                + String.join(", ", attestation.toolNames()));
        } catch (IOException e) {
            println("  Connection failed: " + e.getMessage());
        }
    }

    private void cmdDisconnect() {
        String active = service.activeTargetId();
        if (active == null) {
            println("  No active connection.");
            return;
        }
        service.disconnect();
        println("  Disconnected from " + active);
    }

    private void cmdDoctor(RemoteCommandParser.ParsedCommand cmd) {
        String targetId = cmd.arg(0);
        boolean verbose = cmd.hasOption("verbose");
        boolean json = cmd.hasOption("json");

        if (targetId.isEmpty()) {
            println("  Usage: /remote doctor <targetId> [--verbose|--json]");
            return;
        }

        var report = doctorService.check(targetId);
        if (json) {
            System.out.println(RemoteDoctorService.renderJson(report));
        } else {
            System.out.print(RemoteDoctorService.renderText(report, verbose));
        }
    }

    private void cmdTools() {
        String active = service.activeTargetId();
        if (active == null) {
            println("  No active connection.");
            return;
        }
        Optional<ToolMount> mount = registry.getMount("remote:" + active);
        if (mount.isEmpty()) {
            println("  No remote tools mounted.");
            return;
        }
        println("  Mounted remote tools (" + mount.get().toolNames().size() + "):");
        for (String name : mount.get().toolNames()) {
            var tool = registry.lookup(name);
            String readonly = tool.map(t -> t.isReadOnly() ? " [RO]" : " [RW]").orElse(" [?]");
            println("    - " + name + readonly);
        }
    }

    private void cmdHelp() {
        println("  /remote list                          List registered targets");
        println("  /remote status                        Show active connection");
        println("  /remote inspect [targetId]            Show target details");
        println("  /remote add --from-ssh <alias>        Register from SSH config");
        println("    [--as <id>] [--profile <id>] [--yes]");
        println("    Profiles: app-down-readonly-v1 (default)");
        println("             postgres-diagnosis-readonly-v1");
        println("  /remote add <id> --config <file>      Register from YAML (legacy)");
        println("  /remote remove <targetId>             Remove target");
        println("  /remote connect <targetId>            Connect to target");
        println("  /remote disconnect                    Disconnect");
        println("  /remote doctor <targetId>             Health check");
        println("    [--verbose] [--json]");
        println("  /remote tools                         List mounted tools");
        println("  /remote help                          This help");
        println("");
        println("  Quick start:");
        println("    1. /remote add --from-ssh <alias>");
        println("    2. /remote doctor <target>");
        println("    3. /remote connect <target>");
        println("    4. /remote tools");
        println("    5. /remote disconnect");
        println("");
        println("  Host key issues:");
        println("    unknown → ssh <alias> once to accept, then re-run doctor");
        println("    changed → verify key through trusted channel first");
        println("  Auth failure → check ssh-add -l and authorized_keys");
        println("  Network failure → verify SSH port and connectivity");
        println("");
        println("  Credentials are used via SSH config/Agent references only.");
        println("  No keys, tokens, or paths are stored in Clawkit target files.");
    }

    private static void println(String text) {
        System.out.println(ConsoleRenderer.GRAY + text + ConsoleRenderer.RESET);
    }
}
