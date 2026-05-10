package com.miniclaw.provider.impl.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.provider.LLMConfig;
import com.miniclaw.provider.LLMException;
import com.miniclaw.provider.LLMProvider;
import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.ToolCall;
import com.miniclaw.tools.schema.ToolDefinition;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI 兼容 API 适配器（DeepSeek / 智谱 / Moonshot 等）。
 * 实现 LLMProvider 接口，HTTP + 重试 + JSON 双向转换。
 */
public class OpenAIProvider implements LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAIProvider.class);

    private final LLMConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAIProvider(LLMConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout())
            .build();
    }

    @Override
    public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
        OpenAIRequest request = buildRequest(messages, availableTools);

        final byte[] requestBody;
        try {
            requestBody = objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException e) {
            throw new LLMException("序列化请求失败: " + e.getMessage(), e);
        }

        if (log.isDebugEnabled()) {
            log.debug("OpenAI request: {}", new String(requestBody, StandardCharsets.UTF_8));
        }

        HttpRequest httpRequest = buildHttpRequest(requestBody);
        byte[] responseBody = sendWithRetry(httpRequest);

        OpenAIResponse response;
        try {
            response = objectMapper.readValue(responseBody, OpenAIResponse.class);
        } catch (IOException e) {
            throw new LLMException("解析 LLM 响应失败: " + e.getMessage(), e);
        }

        if (log.isDebugEnabled()) {
            log.debug("OpenAI response model={} id={}", response.model(), response.id());
        }

        return toMessage(response);
    }

    // === 请求构建 ===

    private OpenAIRequest buildRequest(List<Message> messages, List<ToolDefinition> tools) {
        List<OpenAIMessage> openaiMsgs = new ArrayList<>();
        for (Message msg : messages) {
            openaiMsgs.add(toOpenAIMessage(msg));
        }

        List<OpenAITool> openaiTools = null;
        if (tools != null && !tools.isEmpty()) {
            openaiTools = new ArrayList<>();
            for (ToolDefinition td : tools) {
                openaiTools.add(new OpenAITool("function",
                    new OpenAIFunctionDef(td.name(), td.description(), parseSchema(td.inputSchema()))));
            }
        }

        return new OpenAIRequest(config.model(), openaiMsgs, openaiTools, false);
    }

    private HttpRequest buildHttpRequest(byte[] body) {
        String url = config.baseUrl().replaceAll("/+$", "") + "/chat/completions";
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.apiKey())
            .timeout(config.requestTimeout())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    }

    // === 消息转换：内部 → OpenAI JSON ===

    private OpenAIMessage toOpenAIMessage(Message msg) {
        String role = roleToString(msg.role());
        String content = msg.content();

        // 助理 + 工具调用：content 可为 null，tool_calls 放数组
        if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
            List<OpenAIToolCall> toolCalls = new ArrayList<>();
            for (ToolCall tc : msg.toolCalls()) {
                String argsStr;
                try {
                    argsStr = objectMapper.writeValueAsString(tc.arguments());
                } catch (JsonProcessingException e) {
                    throw new LLMException("工具参数序列化失败: " + tc.name(), e);
                }
                toolCalls.add(new OpenAIToolCall(tc.id(), "function",
                    new OpenAIFunction(tc.name(), argsStr)));
            }
            return new OpenAIMessage(role, content, toolCalls, null);
        }

        // 工具结果：带 tool_call_id
        if (msg.toolCallId() != null) {
            return new OpenAIMessage(role, content, null, msg.toolCallId());
        }

        // 普通消息：system / user / assistant 纯文本
        return new OpenAIMessage(role, content, null, null);
    }

    private static String roleToString(com.miniclaw.tools.schema.Role role) {
        return role.name().toLowerCase();
    }

    /** 将 inputSchema 字符串解析为 JsonNode，确保参数被序列化为 JSON 对象。 */
    private JsonNode parseSchema(Object inputSchema) {
        if (inputSchema == null) {
            return objectMapper.createObjectNode();
        }
        if (inputSchema instanceof JsonNode node) {
            return node;
        }
        try {
            return objectMapper.readTree(inputSchema.toString());
        } catch (IOException e) {
            return objectMapper.createObjectNode();
        }
    }

    // === 响应转换：OpenAI JSON → 内部 Message ===

    private Message toMessage(OpenAIResponse response) {
        if (response.choices() == null || response.choices().isEmpty()) {
            throw new LLMException("API 返回了空的 Choices");
        }
        OpenAIChoice choice = response.choices().get(0);
        OpenAIMessage msg = choice.message();

        // 工具调用
        if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
            List<ToolCall> toolCalls = new ArrayList<>();
            for (OpenAIToolCall otc : msg.toolCalls()) {
                JsonNode argsNode;
                try {
                    argsNode = objectMapper.readTree(otc.function().arguments());
                } catch (IOException e) {
                    throw new LLMException("解析工具参数 JSON 失败: " + otc.function().name(), e);
                }
                toolCalls.add(new ToolCall(otc.id(), otc.function().name(), argsNode));
            }
            return msg.content() != null
                ? new Message(com.miniclaw.tools.schema.Role.ASSISTANT, msg.content(), toolCalls, null)
                : Message.assistantWithTools(toolCalls);
        }

        // 纯文本回复
        return Message.assistant(msg.content() != null ? msg.content() : "");
    }

    // === 重试逻辑 ===

    private byte[] sendWithRetry(HttpRequest httpRequest) {
        LLMException lastException = null;

        for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
            if (attempt > 0) {
                long delayMs = (long) Math.pow(2, attempt) * 1000; // 2s, 4s, 8s
                log.warn("LLM 调用重试 {}/{}，等待 {}ms", attempt, config.maxRetries(), delayMs);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LLMException("重试等待被中断", e);
                }
            }

            try {
                HttpResponse<byte[]> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofByteArray());

                int statusCode = response.statusCode();
                byte[] body = response.body();

                if (statusCode == 200) {
                    return body;
                }

                String errorMsg = parseErrorMessage(body, statusCode);
                log.warn("LLM API 返回 HTTP {} (attempt {}/{}): {}",
                    statusCode, attempt + 1, config.maxRetries() + 1, errorMsg);

                if (!shouldRetry(statusCode)) {
                    throw new LLMException("HTTP " + statusCode + ": " + errorMsg);
                }

                lastException = new LLMException(
                    "HTTP " + statusCode + " (attempt " + (attempt + 1) + "): " + errorMsg);

            } catch (LLMException e) {
                // 不可重试错误直接抛
                throw e;
            } catch (IOException e) {
                log.warn("LLM API IO 异常 (attempt {}/{}): {}",
                    attempt + 1, config.maxRetries() + 1, e.getMessage());
                lastException = new LLMException("I/O 错误: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LLMException("请求被中断", e);
            }
        }

        throw new LLMException(
            "LLM 调用失败，已重试 " + config.maxRetries() + " 次。最后错误: " +
            (lastException != null ? lastException.getMessage() : "unknown"),
            lastException);
    }

    private boolean shouldRetry(int statusCode) {
        return statusCode == 429
            || statusCode == 500
            || statusCode == 502
            || statusCode == 503
            || statusCode == 504;
    }

    private String parseErrorMessage(byte[] body, int statusCode) {
        try {
            OpenAIResponse errorResp = objectMapper.readValue(body, OpenAIResponse.class);
            if (errorResp.error() != null && errorResp.error().message() != null) {
                return errorResp.error().message();
            }
        } catch (IOException ignored) {
            // fall through
        }
        String raw = new String(body, StandardCharsets.UTF_8);
        return raw.length() > 200 ? raw.substring(0, 200) + "..." : raw;
    }
}
