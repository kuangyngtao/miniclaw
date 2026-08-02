package com.clawkit.tools.remote;

/**
 * Connection state for a remote MCP target.
 *
 * <p>State machine per REMOTE-0 §7:
 *
 * <pre>{@code
 *   [*] --> DISCONNECTED
 *   DISCONNECTED --> CONNECTING: connect registered target
 *   CONNECTING --> ATTESTING: SSH transport started
 *   CONNECTING --> FAILED: SSH / credential / host key failure
 *   ATTESTING --> READY: exact attestation and atomic tool mount
 *   ATTESTING --> FAILED: any mismatch
 *   READY --> DEGRADED: bounded tool failure, transport still alive
 *   DEGRADED --> READY: later health call succeeds
 *   READY --> DISCONNECTED: explicit disconnect
 *   DEGRADED --> DISCONNECTED: explicit disconnect
 *   FAILED --> CONNECTING: explicit retry creates new generation
 *   DISCONNECTED --> CLOSED: application shutdown
 *   FAILED --> CLOSED: application shutdown
 * }</pre>
 *
 * <p>Design: REMOTE-0 §6.5.
 */
public enum RemoteConnectionState {
    /** No active connection. */
    DISCONNECTED,
    /** SSH transport starting (process launching). */
    CONNECTING,
    /** MCP handshake and attestation in progress. */
    ATTESTING,
    /** Connection ready — tools mounted and usable. */
    READY,
    /** One or more tool calls failed, but transport is still alive and identity is trusted. */
    DEGRADED,
    /** Connection failed — transport closed, no tools mounted. */
    FAILED,
    /** Terminal state — application is shutting down. */
    CLOSED
}
