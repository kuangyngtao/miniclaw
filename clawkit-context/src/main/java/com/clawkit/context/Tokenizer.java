package com.clawkit.context;

import com.clawkit.tools.schema.Message;
import java.util.List;

/**
 * Token 计数器 — 提供与模型一致的真实 token 计数。
 * 优先使用 {@link com.clawkit.context.impl.TiktokenTokenizer}，
 * 不可用时降级到 {@link com.clawkit.context.impl.CharFallbackTokenizer}。
 */
public interface Tokenizer {

    /** 单条消息的 token 数 */
    int countTokens(String text);

    /** 每条消息的 API 格式开销（角色标记、消息边界等），OpenAI 约 3-5 token/msg */
    default int tokensPerMessageOverhead() { return 4; }

    /** 消息列表的总 token 数（含消息开销） */
    default int countTokens(List<Message> messages) {
        int overhead = tokensPerMessageOverhead() * messages.size();
        int total = overhead;
        for (Message msg : messages) {
            String content = msg.content();
            if (content != null && !content.isEmpty()) {
                total += countTokens(content);
            }
        }
        return total;
    }

    /** 编码名称，例如 "cl100k_base"、"o200k_base" */
    String encodingName();
}
