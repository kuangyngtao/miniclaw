package com.clawkit.cli.remote;

/**
 * Sealed interface for how to reach a remote target.
 *
 * <p>Variants:
 * <ul>
 *   <li>{@link OpenSshAliasReference} — preserves SSH Host alias;
 *       OpenSSH resolves config, Agent, certificates, and ProxyJump.</li>
 *   <li>{@link LegacyExplicitEndpointReference} — legacy v1 explicit
 *       host/port/user/key; always uses {@code -F none} for isolation.</li>
 * </ul>
 *
 * <p>Design: PRODUCT-1 §5.1.
 */
public sealed interface RemoteConnectionReference
    permits OpenSshAliasReference, LegacyExplicitEndpointReference {

    /** Human-readable connection type label. */
    String type();
}
