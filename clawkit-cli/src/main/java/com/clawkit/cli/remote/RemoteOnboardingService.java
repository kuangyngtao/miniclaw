package com.clawkit.cli.remote;

import com.clawkit.tools.remote.OpenSshAliasConnectionSpec;
import com.clawkit.tools.remote.RemoteSshConnectionSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Handles the {@code /remote add --from-ssh} workflow with explicit
 * profile selection and re-audit before store write.
 *
 * <p>Design: PRODUCT-1 §7, P1-2.
 */
public class RemoteOnboardingService {

    private final RemoteTargetStore store;
    private final SystemOpenSshFacade sshFacade;

    public RemoteOnboardingService(RemoteTargetStore store, SystemOpenSshFacade sshFacade) {
        this.store = store;
        this.sshFacade = sshFacade;
    }

    /** Discover available SSH aliases. */
    public SshTargetDiscovery.DiscoveryResult discoverTargets() {
        return new SshTargetDiscovery(sshFacade).discover();
    }

    /**
     * Preview a target before registration. Does NOT write to store.
     *
     * @param alias SSH Host alias
     * @param asTargetId desired targetId (null = derive from alias)
     * @param profileId explicit profile manifest ID (null = default APP_DOWN)
     */
    public OnboardingPreview preview(String alias, String asTargetId,
                                      String profileId) throws IOException {
        // Static audit
        var discResult = new SshTargetDiscovery(sshFacade).discover();
        if (!discResult.safe()) {
            throw new IOException("SSH config contains unsafe directives: "
                + String.join("; ", discResult.unsafeReasons()));
        }
        if (!discResult.aliases().contains(alias)) {
            throw new IllegalArgumentException("alias not found in SSH config: " + alias);
        }

        // ssh -G (only after audit passes)
        var gResult = sshFacade.runSshG(alias, "opsro");
        if (gResult.hasUnsafeConfig()) {
            throw new IOException("SSH config for alias contains ProxyCommand");
        }

        String targetId = asTargetId != null && !asTargetId.isEmpty()
            ? asTargetId : alias.replaceAll("[^a-z0-9_-]", "-");

        String pid = profileId != null && !profileId.isEmpty()
            ? profileId : "app-down-readonly-v1";

        var manifest = RemoteProfileCatalog.lookup(pid)
            .orElseThrow(() -> new IllegalArgumentException("unknown profile: " + pid));

        return new OnboardingPreview(targetId, alias, gResult, manifest, discResult);
    }

    /**
     * Register a target. Re-audits SSH config immediately before writing.
     * Caller must have obtained user confirmation (or used --yes).
     */
    public void register(String alias, String targetId, String profileManifestId,
                          boolean replace) throws IOException {
        register(alias, targetId, profileManifestId, replace,
            sshFacade.defaultUserConfigPath());
    }

    /** Register with explicit SSH config file path. */
    public void register(String alias, String targetId, String profileManifestId,
                          boolean replace, Path sshConfigFile) throws IOException {
        // Re-audit before write
        var discResult = new SshTargetDiscovery(sshFacade).discover();
        if (!discResult.safe()) {
            throw new IOException("SSH config is no longer safe — re-run /remote add");
        }

        RemoteProfileCatalog.lookup(profileManifestId)
            .orElseThrow(() -> new IllegalArgumentException("unknown profile: " + profileManifestId));

        var connRef = new OpenSshAliasReference(alias, "opsro", sshConfigFile);
        var reg = new RemoteTargetRegistration(2, targetId, connRef, profileManifestId);
        store.add(reg, replace);
    }

    /** List available read-only profile manifests. */
    public List<RemoteProfileManifest> availableProfiles() {
        return RemoteProfileCatalog.all().stream()
            .filter(m -> m.accessMode() == RemoteAccessMode.READ_ONLY)
            .toList();
    }

    // ── DTO ───────────────────────────────────────────────────────────

    public record OnboardingPreview(
        String targetId,
        String alias,
        SystemOpenSshFacade.SshGResult sshGResult,
        RemoteProfileManifest profileManifest,
        SshTargetDiscovery.DiscoveryResult discoveryResult
    ) {
        /** Render a secret-free summary for user confirmation. */
        public String renderSummary() {
            var g = sshGResult;
            var m = profileManifest;
            return "  Target:     " + targetId + "\n"
                + "  Alias:      " + alias + "\n"
                + "  Host:       " + g.hostname() + ":" + g.port() + "\n"
                + "  User:       " + g.user() + "\n"
                + (g.hasProxyJump() ? "  ProxyJump:  " + g.proxyJump() + "\n" : "")
                + "  Profile:    " + m.displayName() + "\n"
                + "  Capability: " + m.capabilityProfile() + " (" + m.accessMode() + ")\n"
                + "  Contract:   " + m.expectedToolContractHash().substring(0, 12) + "…\n"
                + "  Identity:   " + g.identityFileCount() + " key(s)"
                + (g.agentEnabled() ? " + SSH Agent" : "") + "\n";
        }
    }
}
