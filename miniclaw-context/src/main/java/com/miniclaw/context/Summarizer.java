package com.miniclaw.context;

import com.miniclaw.tools.schema.Message;
import java.util.List;

/**
 * 对话摘要器 — L3 压缩时调用 LLM 将旧轮次对话压缩为文本摘要。
 * 由 engine 层实现，注入 {@link LadderedCompactor}。
 */
@FunctionalInterface
public interface Summarizer {

    /** 将消息列表压缩为一段摘要文本。调用失败应抛异常，由 compactor 降级处理。 */
    String summarize(List<Message> messages);
}
