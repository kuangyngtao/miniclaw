package com.clawkit.cli.remote;

import com.clawkit.tools.remote.CredentialRef;
import com.clawkit.tools.remote.RemoteEndpointConfig;
import com.clawkit.tools.remote.OpenSshAliasConnectionSpec;
import com.clawkit.tools.remote.RemoteSshConnectionSpec;
import com.clawkit.tools.remote.RemoteTargetDescriptor;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Resolves a stored target registration + built-in profile manifest
 * into the runtime types consumed by {@code RemoteMcpSession}.
 *
 * <p>For v2 registrations, the manifest provides all attestation fields.
 * For v1 (legacy), the YAML config already contains the attestation.
 *
 * <p>Design: PRODUCT-1 §4, §5.
 */
public final class RemoteTargetResolver {

    private RemoteTargetResolver() {}

    /**
     * Resolve a v2 registration into a target descriptor.
     *
     * @throws IllegalArgumentException if the manifest is unknown
     */
    public static RemoteTargetDescriptor resolveDescriptor(
            RemoteTargetRegistration reg) {
        var manifest = RemoteProfileCatalog.lookup(reg.profileManifestId())
            .orElseThrow(() -> new IllegalArgumentException(
                "unknown profile manifest: " + reg.profileManifestId()));
        return new RemoteTargetDescriptor(
            reg.targetId(),
            manifest.serverName(),
            manifest.protocolVersion(),
            manifest.probeVersion(),
            manifest.capabilityProfile(),
            manifest.expectedToolSetHash(),
            manifest.expectedToolContractHash()
        );
    }

    /**
     * Resolve a v2 registration into an SSH connection spec.
     */
    public static RemoteSshConnectionSpec resolveConnectionSpec(
            RemoteTargetRegistration reg) {
        var conn = reg.connection();
        return switch (conn) {
            case OpenSshAliasReference alias -> new OpenSshAliasConnectionSpec(
                alias.alias(),
                alias.remoteUser(),
                alias.sshConfigFile(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                32768
            );
            case LegacyExplicitEndpointReference ep -> {
                CredentialRef credRef = CredentialRef.parse(ep.identityFileRef());
                yield new RemoteEndpointConfig(
                    ep.host(), ep.port(), ep.user(),
                    credRef,
                    Path.of(ep.knownHostsFile()),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(15),
                    32768
                );
            }
        };
    }

    /**
     * Resolve a v1 legacy config into a target descriptor.
     */
    public static RemoteTargetDescriptor resolveLegacyDescriptor(
            RemoteTargetConfig config) {
        return config.toTargetDescriptor();
    }

    /**
     * Resolve a v1 legacy config into an SSH connection spec.
     */
    public static RemoteSshConnectionSpec resolveLegacyConnectionSpec(
            RemoteTargetConfig config) {
        CredentialRef credRef = CredentialRef.parse(config.endpoint().identityFileRef());
        return config.toEndpointConfig(credRef);
    }
}
