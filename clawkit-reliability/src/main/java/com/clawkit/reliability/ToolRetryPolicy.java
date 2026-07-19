package com.clawkit.reliability;

/**
 * 工具重试决策接口（P1-A2）。
 *
 * <p>纯函数：相同输入产生相同决策。不持有可变状态。
 * 放置在 clawkit-reliability，只依赖 tools 契约。
 */
@FunctionalInterface
public interface ToolRetryPolicy {
    ToolRetryDecision decide(ToolRetryContext context);
}
