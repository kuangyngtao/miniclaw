package com.clawkit.context;

import com.clawkit.tools.schema.Message;
import java.util.List;

/**
 * 上下文片段：一个来源、一个生命周期的消息集合。
 *
 * <p>ContextPipeline 收集多个 fragment，按 priority 排序后压平为最终的模型消息列表。
 */
public record ContextFragment(
    String id,
    ContextSource source,
    ContextLifecycle lifecycle,
    /** 排序优先级：0=system(最高), 100=user messages, 200=memory, 300=skill, 400=tools */
    int priority,
    List<Message> messages,
    int tokenCount,
    /** 是否允许被 compact 压缩 */
    boolean compactable,
    /** 是否包含敏感内容（如密钥、token） */
    boolean sensitive
) {
    public ContextFragment {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (source == null) throw new IllegalArgumentException("source is required");
        if (lifecycle == null) throw new IllegalArgumentException("lifecycle is required");
        if (messages == null) throw new IllegalArgumentException("messages is required");
        if (priority < 0) throw new IllegalArgumentException("priority must be >= 0");
    }

    /** 默认优先级：按 source 推导 */
    public static int defaultPriority(ContextSource source) {
        return switch (source) {
            case SYSTEM -> 0;
            case WORKSPACE -> 50;
            case SESSION -> 100;
            case RUNTIME -> 150;
            case MEMORY -> 200;
            case SKILL -> 300;
            case TOOLS -> 400;
        };
    }
}
