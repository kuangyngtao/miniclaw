package com.clawkit.reliability;

import java.time.Duration;

/**
 * 可注入的退避等待抽象（P1-A2）。
 * 测试注入无等待实现，避免真实 sleep。
 */
@FunctionalInterface
public interface RetrySleeper {
    /** 等待指定时间；实现应响应中断。 */
    void sleep(Duration duration) throws InterruptedException;

    /** 真实系统 sleep */
    RetrySleeper SYSTEM = duration -> Thread.sleep(duration.toMillis());

    /** 测试用 no-op */
    static RetrySleeper noop() {
        return duration -> {};
    }
}
