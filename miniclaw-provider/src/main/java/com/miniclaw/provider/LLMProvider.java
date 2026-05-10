package com.miniclaw.provider;

import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.ToolDefinition;
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

    /**
     * 阻塞式调用：发送完整上下文历史 + 工具列表，返回模型生成的 Message。
     * 返回的 Message 可能是纯文本回复（content 非空）或工具调用（toolCalls 非空）。
     *
     * @throws LLMException 重试耗尽或遇到不可恢复错误（错误码 A-002）
     */
    Message generate(List<Message> messages, List<ToolDefinition> availableTools);

    /**
     * 流式调用：以 SSE 方式逐步接收 token，每个文本 chunk 回调 onToken，
     * 最后返回完整的 Message（支持工具调用）。
     * 不重试——stream 中断无法重放，失败直接抛 LLMException。
     */
    default Message generateStream(List<Message> messages, List<ToolDefinition> tools,
                                   Consumer<String> onToken) {
        // 默认回退到阻塞式
        return generate(messages, tools);
    }
}
