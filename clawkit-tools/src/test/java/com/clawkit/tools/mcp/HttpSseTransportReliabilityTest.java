package com.clawkit.tools.mcp;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpSseTransportReliabilityTest {

    @Test
    void serverFailureDoesNotRetryPotentialSideEffect() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            requests.incrementAndGet();
            byte[] body = "committed but response failed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var transport = new HttpSseTransport(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
            assertThrows(java.io.IOException.class,
                () -> transport.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"}"));
            assertEquals(1, requests.get(), "transport must never replay tools/call");
        } finally {
            server.stop(0);
        }
    }
}
