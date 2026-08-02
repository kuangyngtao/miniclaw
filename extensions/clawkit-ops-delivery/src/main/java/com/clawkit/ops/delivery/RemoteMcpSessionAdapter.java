package com.clawkit.ops.delivery;

import com.clawkit.ops.loop.OpsReadSession;
import com.clawkit.tools.mcp.McpCallResult;
import com.clawkit.tools.remote.RemoteMcpSession;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Objects;

/**
 * Thin adapter that wraps PRODUCT-1's {@link RemoteMcpSession} as an
 * {@link OpsReadSession} so that the OPS delivery layer can reuse an
 * already-connected PRODUCT-1 session without opening a second SSH connection.
 *
 * <p>OPS-PRODUCT-LOOP-1 §6.
 */
public final class RemoteMcpSessionAdapter implements OpsReadSession {

    private final RemoteMcpSession delegate;

    public RemoteMcpSessionAdapter(RemoteMcpSession delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public McpCallResult callTool(String toolName, ObjectNode arguments) throws IOException {
        return delegate.callTool(toolName, arguments);
    }

    @Override
    public boolean isReady() {
        return delegate.isReady();
    }

    @Override
    public String targetId() {
        return delegate.targetId();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
