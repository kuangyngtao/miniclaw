package com.clawkit.observability;

/**
 * 事件类型字符串常量，作为持久化协议。
 * 不使用 Java 类名，确保重命名不影响已落盘记录。
 */
public final class RunEventType {

    private RunEventType() {}

    public static final String RUN_STARTED = "run_started";
    public static final String RUN_COMPLETED = "run_completed";

    public static final String TURN_STARTED = "turn_started";
    public static final String TURN_COMPLETED = "turn_completed";

    public static final String CONTEXT_PREPARED = "context_prepared";

    public static final String PROVIDER_CALL_STARTED = "provider_call_started";
    public static final String PROVIDER_CALL_COMPLETED = "provider_call_completed";

    public static final String TOOL_INVOKED = "tool_invoked";
    public static final String TOOL_COMPLETED = "tool_completed";

    public static final String APPROVAL_DECIDED = "approval_decided";

    public static final String COMPACT_TRIGGERED = "compact_triggered";
    public static final String COMPACT_COMPLETED = "compact_completed";

    public static final String ATTEMPT_TRANSITION = "attempt_transition";
}
