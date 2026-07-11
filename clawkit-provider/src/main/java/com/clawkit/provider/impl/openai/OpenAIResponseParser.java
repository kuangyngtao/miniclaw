package com.clawkit.provider.impl.openai;

import com.clawkit.provider.LLMException;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.Role;
import com.clawkit.tools.schema.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 非流式 OpenAI 响应解析器。将 OpenAIResponse → 内部 Message。
 * 纯函数，无副作用，可独立测试。
 */
public class OpenAIResponseParser {

    private final ObjectMapper objectMapper;

    public OpenAIResponseParser() {
        this(new ObjectMapper());
    }

    public OpenAIResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将 OpenAI 非流式响应转换为内部 Message。
     * @throws LLMException 如果响应为空或工具参数 JSON 解析失败
     */
    public Message toMessage(OpenAIResponse response) {
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
                    throw new LLMException(
                        "解析工具参数 JSON 失败: " + otc.function().name(), e);
                }
                toolCalls.add(new ToolCall(otc.id(), otc.function().name(), argsNode));
            }
            return msg.content() != null
                ? new Message(Role.ASSISTANT, msg.content(), toolCalls, null)
                : Message.assistantWithTools(toolCalls);
        }

        // 纯文本回复
        return Message.assistant(msg.content() != null ? msg.content() : "");
    }
}
