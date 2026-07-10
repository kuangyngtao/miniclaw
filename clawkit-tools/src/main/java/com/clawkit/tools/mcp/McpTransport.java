package com.clawkit.tools.mcp;

import java.io.IOException;

/** MCP 传输层接口。封装底层通信细节（stdio 子进程 或 HTTP/SSE）。 */
public interface McpTransport extends AutoCloseable {
    void start() throws IOException;
    String send(String jsonRpcRequest) throws IOException;
    void stop();
    boolean isAlive();

    @Override
    default void close() { stop(); }
}
