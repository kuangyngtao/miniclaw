package com.clawkit.observability;

import java.util.function.Consumer;

/**
 * 运行观测记录器接口。
 * 消费 RunEvent 并写入本地存储。
 *
 * <p>AgentEngine 通过 {@link Consumer<RunEvent>} 调用此接口，
 * FileRunRecorder 将事件写入 .clawkit/runs/&lt;run-id&gt;/ 目录。
 * 默认使用 NoopRunRecorder（空操作，不产生 IO 开销）。
 */
public interface RunRecorder extends Consumer<RunEvent> {

    /** 接收并记录运行时事件 */
    @Override
    void accept(RunEvent event);
}
