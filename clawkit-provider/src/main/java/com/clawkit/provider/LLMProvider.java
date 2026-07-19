package com.clawkit.provider;

import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolDefinition;
import java.util.List;

import java.util.function.Consumer;

/**
 * 大模型适配器接口 — 与大模型通信的统一契约。
 *
 * 韧性保证（由实现类负责）:
 * - 超时: 连接 10s + 请求 60s（通过 {@link LLMConfig} 配置）
 * - 重试: 3 次指数退避（2s→4s→8s），仅对 HTTP 429 + 5xx + I/O 超时
 * - 非可恢复错误（401/403/4xx 非 429）不重试，重试耗尽后抛出 LLMException
 */
public interface LLMProvider {

    // ── V2 统一接口（推荐） ──────────────────────────────────────────

    /** V2 阻塞式调用。默认适配旧接口；ExecutionControl 传入取消/deadline 感知实现。 */
    default ModelResponse generate(ModelRequest request) {
        Message msg = generate(request.messages(), request.tools(), request.control());
        var toolCalls = msg.toolCalls();
        var reason = toolCalls != null && !toolCalls.isEmpty()
            ? FinishReason.TOOL_CALLS : FinishReason.STOP;
        return new ModelResponse(msg.content(), toolCalls, reason,
            TokenUsage.EMPTY, new ProviderResponseMetadata("", "", 0));
    }

    /** V2 流式调用。默认适配 generateStream。 */
    default ModelResponse generateStream(ModelRequest request, StreamObserver observer) {
        try {
            Message msg = generateStream(request.messages(), request.tools(),
                observer::onContent, request.control());
            var toolCalls = msg.toolCalls();
            var reason = toolCalls != null && !toolCalls.isEmpty()
                ? FinishReason.TOOL_CALLS : FinishReason.STOP;
            var resp = new ModelResponse(msg.content(), toolCalls, reason,
                TokenUsage.EMPTY, new ProviderResponseMetadata("", "", 0));
            observer.onComplete(resp);
            return resp;
        } catch (LLMException e) {
            observer.onError(toProviderError(e));
            throw e;
        }
    }

    /** 模型能力信息 */
    default ModelCapabilities capabilities() {
        return new ModelCapabilities(getContextWindow(), getEncoding(), false);
    }

    // ── V1 接口（保持向后兼容） ──────────────────────────────────────

    /**
     * 阻塞式调用：发送完整上下文历史 + 工具列表，返回模型生成的 Message。
     *
     * @throws LLMException 重试耗尽或遇到不可恢复错误（错误码 A-002）
     */
    Message generate(List<Message> messages, List<ToolDefinition> availableTools);

    /**
     * P1-G：取消/deadline/预算感知的阻塞式调用。
     * 默认实现忽略 control（旧实现向后兼容）；生产 Provider 应覆盖：
     * 单次请求 timeout 取配置与剩余 deadline 的较小值，每次调用和退避前
     * 重新检查取消，阻塞请求可被取消中断。
     */
    default Message generate(List<Message> messages, List<ToolDefinition> availableTools,
                             com.clawkit.tools.control.ExecutionControl control) {
        return generate(messages, availableTools);
    }

    /**
     * 流式调用：以 SSE 方式逐步接收 token，每个文本 chunk 回调 onToken，
     * 最后返回完整的 Message（支持工具调用）。
     * 不重试——stream 中断无法重放，失败直接抛 LLMException。
     */
    default Message generateStream(List<Message> messages, List<ToolDefinition> tools,
                                   Consumer<String> onToken) {
        return generate(messages, tools);
    }

    /** P1-G：取消/deadline 感知的流式调用。默认实现忽略 control。 */
    default Message generateStream(List<Message> messages, List<ToolDefinition> tools,
                                   Consumer<String> onToken,
                                   com.clawkit.tools.control.ExecutionControl control) {
        return generateStream(messages, tools, onToken);
    }

    /** 模型上下文窗口大小（tokens），默认 128K。 */
    default int getContextWindow() {
        return 128_000;
    }

    /** tokenizer 编码名称，默认 cl100k_base。 */
    default String getEncoding() {
        return "cl100k_base";
    }

    // ── helpers ────────────────────────────────────────────────────

    private static ProviderError toProviderError(LLMException e) {
        if (e.providerError() != null) return e.providerError();
        String msg = e.getMessage();
        if (msg != null && msg.contains("401") || msg != null && msg.contains("403"))
            return new ProviderError.Authentication(msg);
        if (msg != null && msg.contains("429"))
            return new ProviderError.RateLimited(msg, null);
        if (msg != null && msg.contains("timeout"))
            return new ProviderError.Timeout(msg);
        return new ProviderError.Server(msg, 500);
    }
}

/** 模型能力描述 */
record ModelCapabilities(int contextWindow, String encoding, boolean supportsStreaming) {}
