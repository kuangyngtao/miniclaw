package com.clawkit.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 空操作 RunRecorder，默认实现，不产生 IO 开销。
 * 当未配置观测存储时使用。
 */
public final class NoopRunRecorder implements RunRecorder {

    private static final Logger log = LoggerFactory.getLogger(NoopRunRecorder.class);

    @Override
    public void accept(RunEvent event) {
        // no-op: 观测功能未启用
    }
}
