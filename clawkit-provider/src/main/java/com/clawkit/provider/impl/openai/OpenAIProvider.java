package com.clawkit.provider.impl.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.clawkit.provider.LLMConfig;
import com.clawkit.provider.LLMException;
import com.clawkit.provider.LLMProvider;
import com.clawkit.provider.ModelRequest;
import com.clawkit.provider.ModelParameters;
import com.clawkit.provider.ModelResponse;
import com.clawkit.provider.ProviderDialect;
import com.clawkit.provider.ProviderDescriptor;
import com.clawkit.provider.ProviderError;
import com.clawkit.provider.ProviderReasoningMode;
import com.clawkit.provider.ProviderResponseMetadata;
import com.clawkit.provider.StreamObserver;
import com.clawkit.provider.TokenUsage;
import com.clawkit.provider.UsageSource;
import com.clawkit.tools.control.CancelRegistration;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.control.ExecutionHaltedException;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolCall;
import com.clawkit.tools.schema.ToolDefinition;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI 兼容 API 适配器（DeepSeek / 智谱 / Moonshot 等）。
 * 实现 LLMProvider 接口，HTTP + 重试 + JSON 双向转换。
 */
public class OpenAIProvider implements LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAIProvider.class);

    private static final int CIRCUIT_THRESHOLD = 5;
    private static final long CIRCUIT_COOLDOWN_MS = 30_000;

    private final LLMConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenAIResponseParser responseParser;
    private final RetrySleeper retrySleeper;
    private final LongSupplier clockMillis;

    @FunctionalInterface
    interface RetrySleeper { void sleep(long millis) throws InterruptedException; }

    // 熔断器状态（CLOSED → OPEN → HALF_OPEN）
    private int consecutiveFailures;
    private long circuitOpenUntil;

    /** P1-A3：sendWithRetry 内部返回类型 */
    private record SendResult(byte[] body, int retryCount, String model, String id) {}

    /** P0-3：内部 generate 返回类型，metadata 沿返回值传递保证并发安全 */
    private record GenerateResult(Message message, TokenUsage usage,
                                  ProviderResponseMetadata metadata) {}

    private record StreamResult(Message message, TokenUsage usage, String model, String id) {}

    public OpenAIProvider(LLMConfig config) {
        this(config, Thread::sleep, System::currentTimeMillis);
    }

    OpenAIProvider(LLMConfig config, RetrySleeper retrySleeper, LongSupplier clockMillis) {
        this.config = config;
        if ("deepseek-chat".equals(config.model())
            || "deepseek-reasoner".equals(config.model())) {
            log.warn("DeepSeek legacy model alias '{}' is deprecated and will stop working after "
                + "2026-07-24 15:59 UTC; migrate to deepseek-v4-flash with an explicit reasoning mode",
                config.model());
        }
        this.retrySleeper = retrySleeper;
        this.clockMillis = clockMillis;
        this.objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.responseParser = new OpenAIResponseParser(objectMapper);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout())
            .build();
    }

    @Override public int getContextWindow() { return config.contextWindow(); }
    @Override public String getEncoding()    { return config.encoding(); }
    @Override public ProviderDescriptor descriptor() {
        return new ProviderDescriptor(config.dialect(), config.model());
    }

    // === 熔断器 ===

    /**
     * OPEN 状态直接抛异常；HALF_OPEN 允许一次探测；CLOSED 正常通过。
     */
    private void checkCircuit() {
        if (circuitOpenUntil == 0) return; // CLOSED
        long now = clockMillis.getAsLong();
        if (now < circuitOpenUntil) {
            long remaining = (circuitOpenUntil - now) / 1000;
            throw new LLMException("熔断器开启，请等待 " + remaining + "s 后重试");
        }
        // HALF_OPEN: 允许探测
        log.info("熔断器半开，允许探测请求...");
    }

    private synchronized void recordSuccess() {
        if (consecutiveFailures > 0 || circuitOpenUntil > 0) {
            log.info("熔断器恢复，连续失败 {} 次后请求成功", consecutiveFailures);
        }
        consecutiveFailures = 0;
        circuitOpenUntil = 0;
    }

    private synchronized void recordFailure() {
        consecutiveFailures++;
        log.warn("熔断器记录失败: {}/{}", consecutiveFailures, CIRCUIT_THRESHOLD);
        if (consecutiveFailures >= CIRCUIT_THRESHOLD && circuitOpenUntil == 0) {
            circuitOpenUntil = clockMillis.getAsLong() + CIRCUIT_COOLDOWN_MS;
            log.warn("熔断器开启，{}s 内直接快速失败", CIRCUIT_COOLDOWN_MS / 1000);
        } else if (consecutiveFailures >= CIRCUIT_THRESHOLD) {
            // HALF_OPEN 探测失败，重新计时
            circuitOpenUntil = clockMillis.getAsLong() + CIRCUIT_COOLDOWN_MS;
            log.warn("熔断器探测失败，重新开启 {}s", CIRCUIT_COOLDOWN_MS / 1000);
        }
    }

    @Override
    public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
        return generate(messages, availableTools, ExecutionControl.none());
    }

    /** P0-3：V2 generate(ModelRequest) 返回沿调用链传递的真实 metadata */
    @Override
    public ModelResponse generate(ModelRequest request) {
        var gr = generateInternal(request.messages(), request.tools(), request.parameters(), request.control());
        var msg = gr.message();
        var toolCalls = msg.toolCalls();
        var reason = toolCalls != null && !toolCalls.isEmpty()
            ? com.clawkit.provider.FinishReason.TOOL_CALLS : com.clawkit.provider.FinishReason.STOP;
        return new ModelResponse(msg.content(), toolCalls, reason,
            gr.usage(), gr.metadata(), msg.reasoningContent());
    }

    @Override
    public Message generate(List<Message> messages, List<ToolDefinition> availableTools,
                            ExecutionControl control) {
        return generateInternal(messages, availableTools, ModelParameters.DEFAULT, control).message();
    }

    /** P0-3：内部 generate，metadata 沿返回值传递，消除共享可变状态 */
    private GenerateResult generateInternal(List<Message> messages,
                                             List<ToolDefinition> availableTools,
                                             ModelParameters parameters,
                                             ExecutionControl control) {
        control.checkpoint();
        checkCircuit();

        OpenAIRequest request = buildRequest(messages, availableTools, parameters, false);

        final byte[] requestBody;
        try {
            requestBody = objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException e) {
            throw new LLMException("序列化请求失败: " + e.getMessage(), e);
        }

        if (log.isDebugEnabled()) {
            log.debug("OpenAI request: {}", new String(requestBody, StandardCharsets.UTF_8));
        }

        long startMs = System.currentTimeMillis();

        try {
            SendResult sendResult = sendWithRetry(requestBody, control);
            OpenAIResponse response;
            try {
                response = objectMapper.readValue(sendResult.body(), OpenAIResponse.class);
            } catch (IOException e) {
                throw new LLMException("解析 LLM 响应失败: " + e.getMessage(), e);
            }

            if (log.isDebugEnabled()) {
                log.debug("OpenAI response model={} id={} retries={}",
                    sendResult.model(), sendResult.id(), sendResult.retryCount());
            }

            Message result = toMessage(response);
            long elapsed = System.currentTimeMillis() - startMs;
            log.info("[LLM] {} {}ms, {} msgs → {} chars{}",
                config.model(), elapsed, messages.size(),
                result.content() != null ? result.content().length() : 0,
                result.toolCalls() != null ? " + " + result.toolCalls().size() + " tool calls" : "");
            recordSuccess();
            return new GenerateResult(result, toTokenUsage(response.usage()),
                new ProviderResponseMetadata(sendResult.model(), sendResult.id(), sendResult.retryCount()));
        } catch (LLMException e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.warn("[LLM] {} {}ms, {} msgs → FAILED: {}", config.model(), elapsed, messages.size(), e.getMessage());
            recordFailure();
            throw e;
        }
    }

    @Override
    public Message generateStream(List<Message> messages, List<ToolDefinition> availableTools,
                                  Consumer<String> onToken) {
        return generateStream(messages, availableTools, onToken, ExecutionControl.none());
    }

    @Override
    public Message generateStream(List<Message> messages, List<ToolDefinition> availableTools,
                                  Consumer<String> onToken, ExecutionControl control) {
        return generateStreamInternal(messages, availableTools, ModelParameters.DEFAULT,
            onToken, null, control).message();
    }

    @Override
    public ModelResponse generateStream(ModelRequest request, StreamObserver observer) {
        try {
            StreamResult sr = generateStreamInternal(request.messages(), request.tools(),
                request.parameters(), observer::onContent, observer, request.control());
            Message message = sr.message();
            var toolCalls = message.toolCalls();
            var reason = toolCalls != null && !toolCalls.isEmpty()
                ? com.clawkit.provider.FinishReason.TOOL_CALLS
                : com.clawkit.provider.FinishReason.STOP;
            ModelResponse response = new ModelResponse(message.content(), toolCalls, reason,
                sr.usage(), new ProviderResponseMetadata(sr.model(), sr.id(), 0),
                message.reasoningContent());
            observer.onComplete(response);
            return response;
        } catch (LLMException e) {
            ProviderError error = e.providerError() != null
                ? e.providerError() : new ProviderError.Protocol(e.getMessage());
            observer.onError(error);
            throw e;
        }
    }

    private StreamResult generateStreamInternal(List<Message> messages,
                                                List<ToolDefinition> availableTools,
                                                ModelParameters parameters,
                                                Consumer<String> onToken,
                                                StreamObserver observer,
                                                ExecutionControl control) {
        control.checkpoint();
        checkCircuit();

        OpenAIRequest request = buildRequest(messages, availableTools, parameters, true);

        final byte[] requestBody;
        try {
            requestBody = objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException e) {
            throw new LLMException("序列化请求失败: " + e.getMessage(), e);
        }

        if (log.isDebugEnabled()) {
            log.debug("OpenAI stream request: {}", new String(requestBody, StandardCharsets.UTF_8));
        }

        HttpRequest httpRequest = buildHttpRequest(requestBody, effectiveTimeout(control));
        long startMs = System.currentTimeMillis();
        Thread caller = Thread.currentThread();

        try {
            StreamResult result;
            try (CancelRegistration reg = control.onCancel(caller::interrupt)) {
                HttpResponse<java.io.InputStream> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream());

                int status = response.statusCode();
                if (status != 200) {
                    byte[] errBody = response.body().readAllBytes();
                    String errMsg = parseErrorMessage(errBody, status);
                    throw new LLMException("HTTP " + status + ": " + errMsg);
                }

                // 取消时关闭 SSE 连接，让阻塞的 readLine 立即失败
                java.io.InputStream body = response.body();
                try (CancelRegistration streamReg = control.onCancel(() -> {
                    try { body.close(); } catch (IOException ignored) {}
                })) {
                    result = parseSSEStream(body, onToken, observer);
                }
            } finally {
                if (control.isCancelled()) Thread.interrupted(); // 清除控制面注入的中断标志
            }

            long elapsed = System.currentTimeMillis() - startMs;
            log.info("[LLM] {} {}ms stream, {} msgs → {} chars{}",
                config.model(), elapsed, messages.size(),
                result.message().content() != null ? result.message().content().length() : 0,
                result.message().toolCalls() != null
                    ? " + " + result.message().toolCalls().size() + " tool calls" : "");
            recordSuccess();
            return result;

        } catch (LLMException e) {
            if (control.isCancelled()) {
                throw new ExecutionHaltedException(
                    ExecutionHaltedException.Reason.CANCELLED, "流式请求已被取消");
            }
            long elapsed = System.currentTimeMillis() - startMs;
            log.warn("[LLM] {} {}ms stream, {} msgs → FAILED: {}", config.model(), elapsed, messages.size(), e.getMessage());
            recordFailure();
            throw e;
        } catch (IOException e) {
            if (control.isCancelled()) {
                throw new ExecutionHaltedException(
                    ExecutionHaltedException.Reason.CANCELLED, "流式请求已被取消");
            }
            long elapsed = System.currentTimeMillis() - startMs;
            log.warn("[LLM] {} {}ms stream, {} msgs → FAILED: {}", config.model(), elapsed, messages.size(), e.getMessage());
            recordFailure();
            throw new LLMException("流式请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            if (control.isCancelled()) {
                throw new ExecutionHaltedException(
                    ExecutionHaltedException.Reason.CANCELLED, "流式请求已被取消");
            }
            Thread.currentThread().interrupt();
            recordFailure();
            throw new LLMException("流式请求被中断", e);
        }
    }

    /** 解析 SSE 流，累积完整 Message */
    private StreamResult parseSSEStream(java.io.InputStream body, Consumer<String> onToken,
                                        StreamObserver observer)
        throws IOException {

        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        Map<Integer, ToolCallAccum> toolAccum = new HashMap<>();
        String finishReason = null;
        TokenUsage usage = TokenUsage.EMPTY;
        String responseModel = config.model();
        String responseId = "";

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;       // SSE 空行
                if (line.startsWith(": ")) continue; // SSE 注释

                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6);

                if ("[DONE]".equals(data)) break;

                JsonNode chunk;
                try {
                    chunk = objectMapper.readTree(data);
                } catch (IOException e) {
                    continue; // 跳过无法解析的行
                }

                if (chunk.hasNonNull("model")) responseModel = chunk.get("model").asText();
                if (chunk.hasNonNull("id")) responseId = chunk.get("id").asText();
                if (chunk.hasNonNull("usage")) usage = toTokenUsage(chunk.get("usage"));

                JsonNode choices = chunk.get("choices");
                if (choices == null || choices.isEmpty()) continue;

                JsonNode delta = choices.get(0).get("delta");
                if (delta == null) continue;

                // 文本内容 → 回调 onToken
                JsonNode contentNode = delta.get("content");
                if (contentNode != null && !contentNode.asText().isEmpty()) {
                    String text = contentNode.asText();
                    contentBuilder.append(text);
                    onToken.accept(text);
                }

                JsonNode reasoningNode = delta.get("reasoning_content");
                if (reasoningNode != null && !reasoningNode.asText().isEmpty()) {
                    reasoningBuilder.append(reasoningNode.asText());
                }

                // 工具调用增量 → 累积
                JsonNode toolCallsNode = delta.get("tool_calls");
                if (toolCallsNode != null && toolCallsNode.isArray()) {
                    for (JsonNode tc : toolCallsNode) {
                        int idx = tc.get("index").asInt();
                        toolAccum.putIfAbsent(idx, new ToolCallAccum());
                        ToolCallAccum acc = toolAccum.get(idx);

                        JsonNode idNode = tc.get("id");
                        if (idNode != null) acc.id = idNode.asText();

                        JsonNode fnNode = tc.get("function");
                        if (fnNode != null) {
                            JsonNode nameNode = fnNode.get("name");
                            if (nameNode != null) acc.name = nameNode.asText();
                            JsonNode argsNode = fnNode.get("arguments");
                            String argsDelta = argsNode != null ? argsNode.asText() : null;
                            if (argsDelta != null) acc.args.append(argsDelta);
                            if (observer != null) {
                                observer.onToolCallDelta(idx, acc.id, acc.name, argsDelta);
                            }
                        }
                    }
                }

                // finish_reason
                JsonNode frNode = choices.get(0).get("finish_reason");
                if (frNode != null && !frNode.isNull()) {
                    finishReason = frNode.asText();
                }
            }
        }

        // 组装结果
        if (!toolAccum.isEmpty()) {
            // 工具调用完成
            List<ToolCall> toolCalls = new ArrayList<>();
            for (int i = 0; i < toolAccum.size(); i++) {
                ToolCallAccum acc = toolAccum.get(i);
                JsonNode argsNode;
                try {
                    argsNode = objectMapper.readTree(acc.args.toString());
                } catch (IOException e) {
                    argsNode = objectMapper.createObjectNode();
                }
                toolCalls.add(new ToolCall(acc.id, acc.name, argsNode));
            }
            String text = !contentBuilder.isEmpty() ? contentBuilder.toString() : null;
            Message message = Message.assistantWithTools(text, toolCalls,
                !reasoningBuilder.isEmpty() ? reasoningBuilder.toString() : null);
            return new StreamResult(message, usage, responseModel, responseId);
        }

        // 纯文本回复
        Message message = new Message(com.clawkit.tools.schema.Role.ASSISTANT,
            !contentBuilder.isEmpty() ? contentBuilder.toString() : "", null, null,
            !reasoningBuilder.isEmpty() ? reasoningBuilder.toString() : null);
        return new StreamResult(message, usage, responseModel, responseId);
    }

    // === 请求构建 ===

    private OpenAIRequest buildRequest(List<Message> messages, List<ToolDefinition> tools,
                                       ModelParameters parameters, boolean stream) {
        List<OpenAIMessage> openaiMsgs = new ArrayList<>();
        for (Message msg : messages) {
            openaiMsgs.add(toOpenAIMessage(msg));
        }

        List<OpenAITool> openaiTools = null;
        if (tools != null && !tools.isEmpty()) {
            openaiTools = new ArrayList<>();
            for (ToolDefinition td : tools.stream()
                    .sorted(java.util.Comparator.comparing(ToolDefinition::name))
                    .toList()) {
                openaiTools.add(new OpenAITool("function",
                    new OpenAIFunctionDef(td.name(), td.description(),
                        canonicalize(parseSchema(td.inputSchema())))));
            }
        }

        ProviderReasoningMode reasoningMode = parameters != null
            ? parameters.reasoningMode() : ProviderReasoningMode.DISABLED;
        DeepSeekThinking thinking = null;
        String reasoningEffort = null;
        if (config.dialect() == ProviderDialect.DEEPSEEK_V4) {
            switch (reasoningMode) {
                case DISABLED -> thinking = new DeepSeekThinking("disabled");
                case ENABLED_HIGH -> {
                    thinking = new DeepSeekThinking("enabled");
                    reasoningEffort = "high";
                }
                case ENABLED_MAX -> {
                    thinking = new DeepSeekThinking("enabled");
                    reasoningEffort = "max";
                }
                case PROVIDER_DEFAULT -> { }
            }
        } else if (reasoningMode != ProviderReasoningMode.DISABLED
                   && reasoningMode != ProviderReasoningMode.PROVIDER_DEFAULT) {
            throw new LLMException("Reasoning mode requires a provider dialect that supports it");
        }

        return new OpenAIRequest(config.model(), openaiMsgs, openaiTools, stream,
            thinking, reasoningEffort,
            parameters != null ? parameters.temperature() : null,
            parameters != null ? parameters.maxTokens() : null,
            stream ? new OpenAIStreamOptions(true) : null);
    }

    private HttpRequest buildHttpRequest(byte[] body, Duration timeout) {
        String url = config.baseUrl().replaceAll("/+$", "") + "/chat/completions";
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.apiKey())
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    }

    /** 单次请求 timeout = min(配置 timeout, 剩余 deadline)。 */
    private Duration effectiveTimeout(ExecutionControl control) {
        Duration configured = config.requestTimeout();
        var remaining = control.remainingTime();
        if (remaining.isEmpty()) return configured;
        Duration r = remaining.get();
        if (r.compareTo(Duration.ofMillis(1)) < 0) r = Duration.ofMillis(1);
        return r.compareTo(configured) < 0 ? r : configured;
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
            return new OpenAIMessage(role, content, toolCalls, null, msg.reasoningContent());
        }

        // 工具结果：带 tool_call_id
        if (msg.toolCallId() != null) {
            return new OpenAIMessage(role, content, null, msg.toolCallId(), null);
        }

        // 普通消息：system / user / assistant 纯文本
        return new OpenAIMessage(role, content, null, null, msg.reasoningContent());
    }

    private static String roleToString(com.clawkit.tools.schema.Role role) {
        return role.name().toLowerCase();
    }

    /** 将 inputSchema 转为 JsonNode，确保 Map/POJO 不会经由 toString() 丢失结构。 */
    private JsonNode parseSchema(Object inputSchema) {
        if (inputSchema == null) {
            return emptyObjectSchema();
        }
        if (inputSchema instanceof JsonNode node) {
            return node;
        }
        if (!(inputSchema instanceof CharSequence)) {
            return objectMapper.valueToTree(inputSchema);
        }
        try {
            return objectMapper.readTree(inputSchema.toString());
        } catch (IOException e) {
            return emptyObjectSchema();
        }
    }

    private JsonNode emptyObjectSchema() {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        return schema;
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null) return null;
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> sorted.set(name, canonicalize(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(item -> array.add(canonicalize(item)));
            return array;
        }
        return node;
    }

    // === 响应转换：OpenAI JSON → 内部 Message ===

    private Message toMessage(OpenAIResponse response) {
        return responseParser.toMessage(response);
    }

    private static TokenUsage toTokenUsage(OpenAIUsage usage) {
        if (usage == null) return TokenUsage.EMPTY;
        int prompt = valueOrZero(usage.promptTokens());
        int completion = valueOrZero(usage.completionTokens());
        int total = usage.totalTokens() != null ? usage.totalTokens() : prompt + completion;
        int hit = valueOrZero(usage.promptCacheHitTokens());
        int miss = usage.promptCacheMissTokens() != null
            ? usage.promptCacheMissTokens() : Math.max(0, prompt - hit);
        int reasoning = usage.completionTokenDetails() != null
            ? valueOrZero(usage.completionTokenDetails().reasoningTokens()) : 0;
        return new TokenUsage(prompt, completion, total, hit, miss, reasoning, UsageSource.ACTUAL);
    }

    private TokenUsage toTokenUsage(JsonNode usage) {
        if (usage == null || usage.isNull()) return TokenUsage.EMPTY;
        int prompt = intValue(usage, "prompt_tokens");
        int completion = intValue(usage, "completion_tokens");
        int total = usage.hasNonNull("total_tokens")
            ? usage.get("total_tokens").asInt() : prompt + completion;
        int hit = intValue(usage, "prompt_cache_hit_tokens");
        int miss = usage.hasNonNull("prompt_cache_miss_tokens")
            ? usage.get("prompt_cache_miss_tokens").asInt() : Math.max(0, prompt - hit);
        JsonNode details = usage.get("completion_tokens_details");
        int reasoning = details != null ? intValue(details, "reasoning_tokens") : 0;
        return new TokenUsage(prompt, completion, total, hit, miss, reasoning, UsageSource.ACTUAL);
    }

    private static int valueOrZero(Integer value) {
        return value != null ? value : 0;
    }

    private static int intValue(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : 0;
    }

    // === 重试逻辑 ===

    /**
     * P1-G：每次尝试和退避前重新检查取消/deadline/预算；
     * 阻塞中的请求可被取消中断（通过 onCancel → Thread.interrupt）。
     * 中断后请求仍可能已送达服务端并开始处理，因此取消不等于"无副作用失败"，
     * 由上层按 EFFECT_UNKNOWN 语义处理。
     */
    private SendResult sendWithRetry(byte[] requestBody, ExecutionControl control) {
        Thread caller = Thread.currentThread();
        try (CancelRegistration reg = control.onCancel(caller::interrupt)) {
            return sendWithRetryInner(requestBody, control);
        } finally {
            if (control.isCancelled()) {
                Thread.interrupted();
            }
        }
    }

    private SendResult sendWithRetryInner(byte[] requestBody, ExecutionControl control) {
        LLMException lastException = null;
        int retries = 0;

        Long retryAfterMs = null; // P0-3: Retry-After 优先
        for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
            control.checkpoint();
            if (attempt > 0) {
                retries++;
                long delayMs;
                if (retryAfterMs != null) {
                    delayMs = Math.min(retryAfterMs, 60000); // P0-3: Retry-After 优先，上限 60s
                    retryAfterMs = null;
                } else {
                    long cap = Math.min(2000L * (1L << (attempt - 1)), 16000L);
                    delayMs = cap > 0 ? (long) (Math.random() * (cap + 1)) : 0;
                }
                log.warn("LLM 调用重试 {}/{}，等待 {}ms", attempt, config.maxRetries(), delayMs);
                try {
                    retrySleeper.sleep(delayMs);
                } catch (InterruptedException e) {
                    if (control.isCancelled()) {
                        throw new ExecutionHaltedException(
                            ExecutionHaltedException.Reason.CANCELLED, "重试退避期间已被取消");
                    }
                    Thread.currentThread().interrupt();
                    throw new LLMException("DeepSeek retry was interrupted", e,
                        new ProviderError.Cancelled(), retries);
                }
                control.checkpoint();
            }

            HttpRequest httpRequest = buildHttpRequest(requestBody, effectiveTimeout(control));
            try {
                HttpResponse<byte[]> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofByteArray());

                int statusCode = response.statusCode();
                byte[] body = response.body();

                if (statusCode == 200) {
                    String model = config.model();
                    String id = "";
                    try {
                        JsonNode tree = objectMapper.readTree(body);
                        if (tree.has("model")) model = tree.get("model").asText();
                        if (tree.has("id")) id = tree.get("id").asText();
                    } catch (Exception ignored) {}
                    return new SendResult(body, retries, model, id);
                }

                // P0-3: 429 时解析 Retry-After header
                if (statusCode == 429) {
                    retryAfterMs = parseRetryAfter(response);
                }

                String errorMsg = parseErrorMessage(body, statusCode);
                log.warn("LLM API 返回 HTTP {} (attempt {}/{}): {}",
                    statusCode, attempt + 1, config.maxRetries() + 1, errorMsg);

                if (!shouldRetry(statusCode)) {
                    throw classifiedHttpError(statusCode, errorMsg, retries);
                }

                lastException = classifiedHttpError(statusCode,
                    "attempt " + (attempt + 1) + ": " + errorMsg, retries);

            } catch (LLMException e) {
                throw e;
            } catch (IOException e) {
                if (control.isCancelled()) {
                    throw new ExecutionHaltedException(
                        ExecutionHaltedException.Reason.CANCELLED, "请求已被取消");
                }
                log.warn("LLM API IO 异常 (attempt {}/{}): {}",
                    attempt + 1, config.maxRetries() + 1, e.getMessage());
                ProviderError error = e instanceof java.net.http.HttpTimeoutException
                    ? new ProviderError.Timeout("DeepSeek request timed out")
                    : new ProviderError.Network("Cannot reach DeepSeek");
                lastException = new LLMException(errorMessage(error), e, error, retries);
            } catch (InterruptedException e) {
                if (control.isCancelled()) {
                    throw new ExecutionHaltedException(
                        ExecutionHaltedException.Reason.CANCELLED, "请求已被取消");
                }
                Thread.currentThread().interrupt();
                throw new LLMException("请求被中断", e, null, retries);
            }
        }

        throw new LLMException(
            "LLM 调用失败，已重试 " + config.maxRetries() + " 次。最后错误: " +
            (lastException != null ? lastException.getMessage() : "unknown"),
            lastException, lastException != null ? lastException.providerError() : null, retries);
    }

    private LLMException classifiedHttpError(int statusCode, String detail, int retryCount) {
        String lower = detail == null ? "" : detail.toLowerCase();
        ProviderError error = switch (statusCode) {
            case 401, 403 -> new ProviderError.Authentication("DeepSeek authentication failed");
            case 429 -> new ProviderError.RateLimited("DeepSeek rate limit exceeded", null);
            default -> lower.contains("context") && (lower.contains("length") || lower.contains("token"))
                ? new ProviderError.ContextLengthExceeded("DeepSeek context length exceeded")
                : statusCode >= 400 && statusCode < 500
                    ? new ProviderError.Protocol("DeepSeek rejected the request")
                    : new ProviderError.Server("DeepSeek service error", statusCode);
        };
        return new LLMException("HTTP " + statusCode + ": " + sanitize(detail), null, error, retryCount);
    }

    /** P0-3：解析 Retry-After header（秒数或 HTTP-date）。返回毫秒，失败返回 null。 */
    private static Long parseRetryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
            .map(val -> {
                try { return Long.parseLong(val.trim()) * 1000; } catch (NumberFormatException e) { return null; }
            }).orElse(null);
    }

    private static String errorMessage(ProviderError error) {
        return switch (error) {
            case ProviderError.Timeout ignored -> "DeepSeek request timed out";
            case ProviderError.Network ignored -> "Cannot reach DeepSeek";
            default -> "DeepSeek request failed";
        };
    }

    private static String sanitize(String value) {
        if (value == null) return "unknown";
        String safe = value.replaceAll("(?i)(bearer\\s+|sk-)[A-Za-z0-9._-]+", "$1***");
        return safe.length() > 200 ? safe.substring(0, 200) + "..." : safe;
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

    /** 流式工具调用增量累加器 */
    private static class ToolCallAccum {
        String id;
        String name;
        final StringBuilder args = new StringBuilder();
    }
}
