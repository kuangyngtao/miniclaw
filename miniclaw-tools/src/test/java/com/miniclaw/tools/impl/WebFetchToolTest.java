package com.miniclaw.tools.impl;

import com.miniclaw.tools.Result;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebFetchToolTest {

    private final WebFetchTool tool = new WebFetchTool();

    private static String args(String url, String prompt) {
        return "{\"url\":\"" + url + "\",\"prompt\":\"" + prompt + "\"}";
    }

    // === 参数校验 ===

    @Test
    void shouldRejectMissingUrl() {
        Result<String> r = tool.execute("{\"prompt\":\"提取内容\"}");
        assertThat(r).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<String>) r).error().message()).contains("url");
    }

    @Test
    void shouldRejectMissingPrompt() {
        Result<String> r = tool.execute("{\"url\":\"https://example.com\"}");
        assertThat(r).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<String>) r).error().message()).contains("prompt");
    }

    @Test
    void shouldRejectInvalidJson() {
        Result<String> r = tool.execute("not json");
        assertThat(r).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<String>) r).error().errorCode()).isEqualTo("T-002");
    }

    @Test
    void shouldRejectInvalidUrl() {
        Result<String> r = tool.execute(args("not-a-url", "test"));
        assertThat(r).isInstanceOf(Result.Err.class);
    }

    @Test
    void shouldRejectNonHttpScheme() {
        Result<String> r = tool.execute(args("ftp://example.com/file", "test"));
        assertThat(r).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<String>) r).error().message()).contains("http");
    }

    // === HTTP 请求 ===

    @Test
    void shouldFetchAndExtractText() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/test", exchange -> {
            byte[] body = """
                <html>
                <head><title>Test Page</title></head>
                <body>
                  <h1>Hello World</h1>
                  <p>This is a <strong>test</strong> page.</p>
                  <script>console.log('removed')</script>
                  <style>body { color: red; }</style>
                </body>
                </html>
                """.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(null);
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/test";

        try {
            Result<String> r = tool.execute(args(url, "提取标题"));
            assertThat(r).isInstanceOf(Result.Ok.class);
            String text = ((Result.Ok<String>) r).data();
            assertThat(text).contains("Hello World");
            assertThat(text).contains("test page");
            assertThat(text).doesNotContain("console.log");
            assertThat(text).doesNotContain("body {");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldHandlePlainText() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/plain", exchange -> {
            byte[] body = "plain text response".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(null);
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/plain";

        try {
            Result<String> r = tool.execute(args(url, "test"));
            assertThat(r).isInstanceOf(Result.Ok.class);
            assertThat(((Result.Ok<String>) r).data()).contains("plain text response");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldHandleHttpError() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/notfound", exchange -> {
            byte[] body = "Not Found".getBytes();
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(null);
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/notfound";

        try {
            Result<String> r = tool.execute(args(url, "test"));
            assertThat(r).isInstanceOf(Result.Err.class);
            assertThat(((Result.Err<String>) r).error().message()).contains("404");
        } finally {
            server.stop(0);
        }
    }

    // === 截断 ===

    @Test
    void shouldTruncateLongContent() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9000; i++) sb.append('x');
        String bodyContent = "<html><body>" + sb + "</body></html>";

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/long", exchange -> {
            byte[] body = bodyContent.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(null);
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/long";

        try {
            Result<String> r = tool.execute(args(url, "test"));
            assertThat(r).isInstanceOf(Result.Ok.class);
            String text = ((Result.Ok<String>) r).data();
            assertThat(text).contains("[truncated at 8000 chars");
        } finally {
            server.stop(0);
        }
    }

    // === 缓存 ===

    @Test
    void shouldCacheAndReuse() throws IOException {
        int[] count = {0};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/cached", exchange -> {
            count[0]++;
            byte[] body = ("<html><body>hit " + count[0] + "</body></html>").getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(null);
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/cached";

        try {
            tool.execute(args(url, "test"));
            tool.execute(args(url, "test"));
            tool.execute(args(url, "test"));
            assertThat(count[0]).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldBeReadOnly() {
        assertThat(tool.isReadOnly()).isTrue();
    }

    @Test
    void shouldHaveCorrectName() {
        assertThat(tool.name()).isEqualTo("web_fetch");
    }
}
