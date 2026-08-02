package com.clawkit.ops.loop;

import com.clawkit.tools.mcp.McpCallResult;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/**
 * Minimal read-only MCP session abstraction consumed by discovery/diagnosis.
 *
 * <p>Decouples {@link RemoteDiscoveryCoordinator} from concrete session types
 * so that PRODUCT-1 {@code RemoteMcpSession} and OPS {@code RemoteOpsSession}
 * can both be used without forcing ops-loop to depend on CLI modules.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link RemoteOpsSession} — direct implementation (same module)</li>
 *   <li>{@code RemoteMcpSessionAdapter} (in {@code clawkit-ops-delivery}) —
 *       thin adapter over PRODUCT-1's {@code RemoteMcpSession}</li>
 * </ul>
 *
 * <p>OPS-PRODUCT-LOOP-1 §6.
 */
public interface OpsReadSession extends AutoCloseable {

    /** Call a read-only tool on the remote server. */
    McpCallResult callTool(String toolName, ObjectNode arguments) throws IOException;

    /** Whether the session is in READY state and can accept tool calls. */
    boolean isReady();

    /** The logical target identifier (no host/key/credential data). */
    String targetId();

    /** Gracefully close the session. Idempotent. */
    @Override
    void close();
}
