package com.miniclaw.provider;

import com.miniclaw.tools.schema.Message;
import com.miniclaw.tools.schema.ToolDefinition;
import java.util.List;

/**
 * 大模型适配器接口 — 与大模型通信的统一契约。
 *
 * 韧性保证（由实现类负责）:
 * - 超时: 连接 10s + 请求 60s（通过 {@link LLMConfig} 配置）
 * - 重试: 3 次指数退避（2s→4s→8s），仅对 HTTP 429 + 5xx + I/O 超时
 * - 非可恢复错误（401/403/4xx 非 429）不重试
 */
public interface LLMProvider {

    /**
     * 将当前上下文历史 + 可用工具列表发送给大模型，返回模型生成的 Message。
     * 返回的 Message 可能是纯文本回复（content 非空）或工具调用（toolCalls 非空）。
     */
    Message generate(List<Message> messages, List<ToolDefinition> availableTools);
}
