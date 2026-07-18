package com.clawkit.engine;

import com.clawkit.memory.MemoryEntry;
import com.clawkit.tools.schema.Message;
import java.util.List;
import java.util.Set;

/**
 * 记忆钩子：run 前后的记忆召回和提取。
 *
 * <p>beforeRun: 根据任务描述召回相关记忆，注入为上下文片段。
 * afterRun: 从对话历史中提取新记忆并保存。
 */
public interface MemoryHooks {

    /** 召回请求 */
    record MemoryRecallRequest(String task, int maxEntries, Set<String> excludeNames) {
        public MemoryRecallRequest {
            if (maxEntries < 0) throw new IllegalArgumentException("maxEntries must be >= 0");
            excludeNames = excludeNames == null ? Set.of() : Set.copyOf(excludeNames);
        }
    }

    /** 提取请求 */
    record MemoryExtractionRequest(
        List<Message> contextHistory,
        int turnCount,
        RunScope scope,
        boolean force,
        int maxEntries
    ) {
        public MemoryExtractionRequest {
            contextHistory = contextHistory == null ? List.of() : List.copyOf(contextHistory);
            if (turnCount < 0) throw new IllegalArgumentException("turnCount must be >= 0");
            if (maxEntries <= 0) maxEntries = force ? 5 : 3;
        }

        public MemoryExtractionRequest(List<Message> contextHistory, int turnCount) {
            this(contextHistory, turnCount, null, false, 3);
        }
    }

    /** 保存结果 */
    record MemorySaveResult(int saved, int skipped, int conflicts) {
        public static final MemorySaveResult EMPTY = new MemorySaveResult(0, 0, 0);
    }

    /**
     * Run 前召回相关记忆。
     *
     * @return 记忆上下文消息（可注入到 ContextPipeline），失败返回空列表
     */
    List<Message> beforeRun(MemoryRecallRequest request);

    /**
     * Run 后提取并保存新记忆。
     *
     * @return 保存结果统计
     */
    MemorySaveResult afterRun(MemoryExtractionRequest request);

    /** Explicit memory writes requested by the model's memory_save tool. */
    default MemorySaveResult remember(MemoryEntry entry) { return MemorySaveResult.EMPTY; }

    /** Current lightweight index used by the stable prompt. */
    default String memoryIndex() { return ""; }

    default boolean available() { return false; }
}
