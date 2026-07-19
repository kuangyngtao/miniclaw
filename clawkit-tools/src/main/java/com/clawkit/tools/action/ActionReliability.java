package com.clawkit.tools.action;

/**
 * 动作恢复能力声明。三项均为保守默认 false；
 * 只有可信的本地实现（非 MCP hint）才能声明为 true。
 */
public record ActionReliability(
    boolean locallyProvenIdempotent,
    boolean serverDedupSupported,
    boolean reconcileSupported
) {
    /** 保守默认：不幂等、无服务端去重、不可 reconcile。 */
    public static ActionReliability none() {
        return new ActionReliability(false, false, false);
    }

    /** 本地可信的设置型幂等操作，且可重新采证。 */
    public static ActionReliability idempotentSetter() {
        return new ActionReliability(true, false, true);
    }
}
