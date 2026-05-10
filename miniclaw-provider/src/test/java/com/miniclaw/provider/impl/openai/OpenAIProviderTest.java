package com.miniclaw.provider.impl.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.provider.LLMConfig;
import com.miniclaw.provider.LLMException;
import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.ToolCall;
import com.miniclaw.tools.schema.ToolDefinition;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAIProviderTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static HttpServer server;
    private static int port;
    private static Queue<StubResponse> responseQueue;
    private static List<String> capturedBodies;

    private OpenAIProvider provider;
    private LLMConfig config;

    record StubResponse(int statusCode, String body) {}

    @BeforeAll
    static void startServer() throws IOException {
        responseQueue = new ConcurrentLinkedQueue<>();
        capturedBodies = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] rawBody = exchange.getRequestBody().readAllBytes();
            capturedBodies.add(new String(rawBody, StandardCharsets.UTF_8));
            StubResponse stub = responseQueue.poll();
            byte[] resp = (stub != null ? stub.body() : "{\"error\":{\"message\":\"no stub\"}}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(stub != null ? stub.statusCode() : 500, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void setUp() {
        responseQueue.clear();
        capturedBodies.clear();
        config = LLMConfig.builder()
            .apiKey("sk-test")
            .baseUrl("http://localhost:" + port)
            .model("deepseek-chat")
            .requestTimeout(java.time.Duration.ofSeconds(5))
            .build();
        provider = new OpenAIProvider(config);
    }

    // === Test 1: 纯文本回复 ===
    @Test
    void shouldReturnTextResponse() {
        responseQueue.add(new StubResponse(200,
            "{\"id\":\"chatcmpl-1\",\"model\":\"deepseek-chat\",\"choices\":["
            + "{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"你好，有什么可以帮助你的？\"},"
            + "\"finish_reason\":\"stop\"}]}"));

        Message result = provider.generate(
            List.of(Message.user("你好")), List.of());

        assertThat(result.content()).contains("有什么可以帮助你的");
        assertThat(result.toolCalls()).isNull();
    }

    // === Test 2: 工具调用回复 ===
    @Test
    void shouldReturnToolCallResponse() {
        responseQueue.add(new StubResponse(200,
            "{\"id\":\"chatcmpl-2\",\"model\":\"deepseek-chat\",\"choices\":["
            + "{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,"
            + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
            + "\"function\":{\"name\":\"bash\",\"arguments\":\"{\\\"command\\\":\\\"ls -la\\\"}\"}}]},"
            + "\"finish_reason\":\"tool_calls\"}]}"));

        Message result = provider.generate(
            List.of(Message.user("列出文件")),
            List.of(new ToolDefinition("bash", "execute shell", null)));

        assertThat(result.toolCalls()).isNotEmpty();
        ToolCall tc = result.toolCalls().get(0);
        assertThat(tc.id()).isEqualTo("call_1");
        assertThat(tc.name()).isEqualTo("bash");
        assertThat(tc.arguments().get("command").asText()).isEqualTo("ls -la");
    }

    // === Test 3: 请求体包含工具定义 ===
    @Test
    void shouldIncludeToolsInRequestBody() {
        responseQueue.add(new StubResponse(200,
            "{\"id\":\"x\",\"model\":\"x\",\"choices\":["
            + "{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
            + "\"finish_reason\":\"stop\"}]}"));

        ToolDefinition toolDef = new ToolDefinition("read_file",
            "Read a file", mapper.createObjectNode().put("type", "object"));
        provider.generate(List.of(Message.user("read")), List.of(toolDef));

        String body = capturedBodies.get(0);
        assertThat(body).contains("\"tools\"");
        assertThat(body).contains("\"name\":\"read_file\"");
        assertThat(body).contains("\"type\":\"function\"");
    }

    // === Test 4: 请求体包含消息历史 ===
    @Test
    void shouldSerializeMessagesCorrectly() {
        responseQueue.add(new StubResponse(200,
            "{\"id\":\"x\",\"model\":\"x\",\"choices\":["
            + "{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"done\"},"
            + "\"finish_reason\":\"stop\"}]}"));

        List<Message> msgs = List.of(
            Message.system("You are helpful"),
            Message.user("hello"),
            Message.assistant("hi there"),
            Message.toolResult("call_1", "file contents here"));

        provider.generate(msgs, List.of());

        String body = capturedBodies.get(0);
        assertThat(body).contains("\"role\":\"system\"");
        assertThat(body).contains("\"role\":\"user\"");
        assertThat(body).contains("\"role\":\"assistant\"");
        assertThat(body).contains("\"role\":\"tool\"");
        assertThat(body).contains("\"tool_call_id\":\"call_1\"");
        assertThat(body).contains("\"stream\":false");
    }

    // === Test 5: 不重试 400 ===
    @Test
    void shouldNotRetryOn400() {
        responseQueue.add(new StubResponse(400,
            "{\"error\":{\"message\":\"invalid request\",\"type\":\"invalid_request_error\"}}"));

        assertThatThrownBy(() ->
            provider.generate(List.of(Message.user("x")), List.of()))
            .isInstanceOf(LLMException.class)
            .hasMessageContaining("HTTP 400");
    }

    // === Test 6: 不重试 401 ===
    @Test
    void shouldNotRetryOn401() {
        responseQueue.add(new StubResponse(401,
            "{\"error\":{\"message\":\"unauthorized\",\"type\":\"authentication_error\"}}"));

        assertThatThrownBy(() ->
            provider.generate(List.of(Message.user("x")), List.of()))
            .isInstanceOf(LLMException.class)
            .hasMessageContaining("HTTP 401");
    }

    // === Test 7: 429 重试后成功 ===
    @Test
    void shouldRetryThenSucceedOn429() {
        responseQueue.add(new StubResponse(429,
            "{\"error\":{\"message\":\"rate limited\",\"type\":\"rate_limit_error\"}}"));
        responseQueue.add(new StubResponse(200,
            "{\"id\":\"ok\",\"model\":\"x\",\"choices\":["
            + "{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"终于成功了\"},"
            + "\"finish_reason\":\"stop\"}]}"));

        Message result = provider.generate(List.of(Message.user("x")), List.of());

        assertThat(result.content()).contains("终于成功");
    }

    // === Test 8: 重试耗尽后抛异常 ===
    @Test
    void shouldThrowAfterRetriesExhausted() {
        for (int i = 0; i <= 3; i++) { // maxRetries=3 → 4 次尝试
            responseQueue.add(new StubResponse(500,
                "{\"error\":{\"message\":\"server error\",\"type\":\"server_error\"}}"));
        }

        assertThatThrownBy(() ->
            provider.generate(List.of(Message.user("x")), List.of()))
            .isInstanceOf(LLMException.class)
            .hasMessageContaining("已重试 3 次");
    }

    // === Test 9: 空工具列表不发送 tools 字段 ===
    @Test
    void shouldNotSendToolsFieldWhenEmpty() {
        responseQueue.add(new StubResponse(200,
            "{\"id\":\"x\",\"model\":\"x\",\"choices\":["
            + "{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
            + "\"finish_reason\":\"stop\"}]}"));

        provider.generate(List.of(Message.user("x")), List.of());

        String body = capturedBodies.get(0);
        assertThat(body).doesNotContain("\"tools\"");
    }
}
