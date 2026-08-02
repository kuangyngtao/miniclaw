package com.clawkit.tools.remote;

import java.time.Duration;
import java.util.List;

/**
 * Narrow interface for SSH connection parameters consumed by {@link RemoteMcpSession}.
 *
 * <p>Unifies two modes:
 * <ul>
 *   <li>Legacy explicit endpoint ({@link RemoteEndpointConfig}) — host/port/user/key,
 *       always uses {@code -F none} to isolate from user SSH config.</li>
 *   <li>OpenSSH alias ({@link OpenSshAliasConnectionSpec}) — preserves the alias,
 *       lets OpenSSH resolve config, ProxyJump, Agent, and certificates.</li>
 * </ul>
 *
 * <p>Implementations must build argument lists using {@link List List&lt;String&gt;},
 * never shell string concatenation. All arguments produced by this interface are
 * combined with mandatory safety overrides from {@link RemoteSshSafetyPolicy}
 * before process creation.
 *
 * <p>Design: PRODUCT-1 §5.2, REMOTE-0 §6.2.
 */
public interface RemoteSshConnectionSpec {

    /**
     * SSH command-line arguments (excluding the {@code ssh} executable).
     *
     * <p>Must be a defensive copy — callers must not mutate the returned list.
     * The safety policy layer will prepend its own arguments before this
     * method's output.
     */
    List<String> sshArgs();

    /**
     * Sanitized reference for human-readable messages.
     * Must not contain key paths, IP addresses (alias mode), or credential material.
     */
    String safeRef();

    /** SSH connection timeout. */
    Duration connectTimeout();

    /** Per-request timeout for MCP calls over this connection. */
    Duration requestTimeout();

    /** Maximum output bytes for remote tool calls. */
    int maxOutputBytes();
}
