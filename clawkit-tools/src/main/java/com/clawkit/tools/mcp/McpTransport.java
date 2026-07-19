package com.clawkit.tools.mcp;

import com.clawkit.tools.control.ExecutionControl;
import java.io.IOException;

/** MCP 传输层接口。封装底层通信细节（stdio 子进程 或 HTTP/SSE）。 */
public interface McpTransport extends AutoCloseable {
    void start() throws IOException;
    String send(String jsonRpcRequest) throws IOException;
    default String send(String jsonRpcRequest, ExecutionControl control) throws IOException {
        ExecutionControl effective = control != null ? control : ExecutionControl.none();
        effective.checkpoint();
        return send(jsonRpcRequest);
    }
    void stop();
    boolean isAlive();

    @Override
    default void close() { stop(); }
}
