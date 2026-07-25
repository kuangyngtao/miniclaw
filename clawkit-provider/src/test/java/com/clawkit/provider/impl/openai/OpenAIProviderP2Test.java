package com.clawkit.provider.impl.openai;

import com.clawkit.provider.LLMConfig;
import com.clawkit.provider.ModelParameters;
import com.clawkit.provider.ModelRequest;
import com.clawkit.provider.ModelResponse;
import com.clawkit.provider.ProviderDialect;
import com.clawkit.provider.ProviderError;
import com.clawkit.provider.ProviderReasoningMode;
import com.clawkit.provider.StreamObserver;
import com.clawkit.provider.UsageSource;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAIProviderP2Test {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private AtomicReference<String> responseBody;
    private AtomicReference<String> requestBody;
    private OpenAIProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        responseBody = new AtomicReference<>();
        requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        LLMConfig config = LLMConfig.builder()
            .apiKey("sk-test")
            .baseUrl("http://localhost:" + server.getAddress().getPort())
            .model("deepseek-v4-flash")
            .dialect(ProviderDialect.DEEPSEEK_V4)
            .requestTimeout(Duration.ofSeconds(5))
            .build();
        provider = new OpenAIProvider(config, millis -> { }, System::currentTimeMillis);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void defaultsSelectDeepSeekV4FlashDialect() {
        LLMConfig defaults = LLMConfig.defaults("sk-test");

        assertThat(defaults.model()).isEqualTo("deepseek-v4-flash");
        assertThat(defaults.dialect()).isEqualTo(ProviderDialect.DEEPSEEK_V4);
        assertThat(defaults.contextWindow()).isEqualTo(1_000_000);
    }

    @Test
    void serializesExplicitDeepSeekReasoningMode() throws Exception {
        responseBody.set(textResponse());
        ModelParameters parameters = ModelParameters.DEFAULT
            .withReasoningMode(ProviderReasoningMode.ENABLED_MAX);

        provider.generate(new ModelRequest(List.of(Message.user("solve")), List.of(),
            parameters, ExecutionControl.none()));

        var body = mapper.readTree(requestBody.get());
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("max");
    }

    @Test
    void returnsActualUsageAndReasoningContent() {
        responseBody.set("{\"id\":\"usage-1\",\"model\":\"deepseek-v4-flash\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
            + "\"reasoning_content\":\"inspect evidence\",\"content\":\"done\"},"
            + "\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":30,"
            + "\"total_tokens\":150,\"prompt_cache_hit_tokens\":80,"
            + "\"prompt_cache_miss_tokens\":40,"
            + "\"completion_tokens_details\":{\"reasoning_tokens\":18}}}");

        ModelResponse result = provider.generate(ModelRequest.of(
            List.of(Message.user("diagnose")), List.of()));

        assertThat(result.reasoningContent()).isEqualTo("inspect evidence");
        assertThat(result.usage().source()).isEqualTo(UsageSource.ACTUAL);
        assertThat(result.usage().promptTokens()).isEqualTo(120);
        assertThat(result.usage().promptCacheHitTokens()).isEqualTo(80);
        assertThat(result.usage().promptCacheMissTokens()).isEqualTo(40);
        assertThat(result.usage().reasoningTokens()).isEqualTo(18);
    }

    @Test
    void collectsStreamingUsageAndReasoningContent() {
        responseBody.set("data: {\"id\":\"stream-1\",\"model\":\"deepseek-v4-flash\","
            + "\"choices\":[{\"delta\":{\"reasoning_content\":\"check \"}}]}\n\n"
            + "data: {\"id\":\"stream-1\",\"model\":\"deepseek-v4-flash\","
            + "\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: {\"id\":\"stream-1\",\"model\":\"deepseek-v4-flash\",\"choices\":[],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":6,\"total_tokens\":16,"
            + "\"prompt_cache_hit_tokens\":7,\"prompt_cache_miss_tokens\":3,"
            + "\"completion_tokens_details\":{\"reasoning_tokens\":2}}}\n\n"
            + "data: [DONE]\n\n");
        AtomicReference<ModelResponse> completed = new AtomicReference<>();

        ModelResponse result = provider.generateStream(ModelRequest.of(
            List.of(Message.user("stream")), List.of()), new StreamObserver() {
                @Override public void onContent(String delta) { }
                @Override public void onToolCallDelta(int index, String toolCallId,
                                                       String toolName, String argumentsDelta) { }
                @Override public void onComplete(ModelResponse response) { completed.set(response); }
                @Override public void onError(ProviderError error) { }
            });

        assertThat(result.content()).isEqualTo("answer");
        assertThat(result.reasoningContent()).isEqualTo("check ");
        assertThat(result.usage().totalTokens()).isEqualTo(16);
        assertThat(result.usage().promptCacheHitTokens()).isEqualTo(7);
        assertThat(completed.get()).isSameAs(result);
        assertThat(requestBody.get()).contains("\"stream_options\":{\"include_usage\":true}");
    }

    @Test
    void carriesReasoningContentAcrossToolTurn() {
        responseBody.set(textResponse());
        Message assistant = new Message(com.clawkit.tools.schema.Role.ASSISTANT, null,
            List.of(new com.clawkit.tools.schema.ToolCall("call-1", "read", mapper.createObjectNode())),
            null, "need evidence");

        provider.generate(List.of(assistant, Message.toolResult("call-1", "evidence")), List.of());

        assertThat(requestBody.get()).contains("\"reasoning_content\":\"need evidence\"");
    }

    @Test
    void serializesToolsAndSchemaKeysDeterministically() throws Exception {
        responseBody.set(textResponse());
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("required", List.of("path"));
        schema.put("properties", Map.of("path", Map.of("type", "string")));
        schema.put("type", "object");

        provider.generate(List.of(Message.user("tools")), List.of(
            new ToolDefinition("zeta", "z", Map.of()),
            new ToolDefinition("alpha", "a", schema)));

        var body = mapper.readTree(requestBody.get());
        assertThat(body.path("tools").path(0).path("function").path("name").asText())
            .isEqualTo("alpha");
        assertThat(requestBody.get().indexOf("\"properties\""))
            .isLessThan(requestBody.get().indexOf("\"required\""));
    }

    private static String textResponse() {
        return "{\"id\":\"x\",\"model\":\"deepseek-v4-flash\",\"choices\":["
            + "{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
            + "\"finish_reason\":\"stop\"}]}";
    }
}
