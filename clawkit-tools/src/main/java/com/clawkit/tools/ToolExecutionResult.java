package com.clawkit.tools;

import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.action.FailureClass;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 工具执行的结构化结果，取代旧的字符串输出。
 * 所有工具调用统一通过此 record 返回结果。
 *
 * <p>V2：使用 {@link ToolExecutionStatus} 唯一终态替代 boolean error + boolean timedOut。
 * 增加 {@link ToolError}、{@link ToolOutputStats}、{@link ApprovalRecord} 和 auditId。
 *
 * <p>V3（P1-G）：增加副作用确定性 {@link EffectCertainty}、确定性失败分类
 * {@link FailureClass}、截断保真 {@link OutputEnvelope} 和 attemptId。
 * 未显式给出时按保守规则派生：只读工具 → NO_EFFECT_CONFIRMED；
 * 其余从 status 派生的 FailureClass 取固有确定性，绝不高估"无副作用"。
 */
public record ToolExecutionResult(
    String toolCallId,
    String toolName,
    String output,
    boolean error,
    String errorCode,
    long durationMs,
    int outputBytes,
    boolean truncated,
    boolean timedOut,
    Integer exitCode,
    ToolMetadata metadata,
    // ── V2 字段 ──────────────────────────────────────────────────
    ToolExecutionStatus status,
    ToolError toolError,
    ToolOutputStats outputStats,
    ApprovalRecord approval,
    String auditId,
    // ── V3 字段（P1-G） ──────────────────────────────────────────
    EffectCertainty effectCertainty,
    FailureClass failureClass,
    OutputEnvelope outputEnvelope,
    String attemptId
) {
    /** 保守派生：未显式声明的 V3 字段从 status/metadata 推导。 */
    public ToolExecutionResult {
        if (failureClass == null && status != null) {
            failureClass = deriveFailureClass(status);
        }
        if (effectCertainty == null) {
            effectCertainty = deriveCertainty(failureClass, metadata);
        }
    }

    // ── V2 规范构造器（旧字段从 status 派生，构造器链保证一致性） ──

    /** V2 规范构造器 */
    public ToolExecutionResult(
        String toolCallId,
        String toolName,
        String output,
        ToolExecutionStatus status,
        ToolError toolError,
        long durationMs,
        ToolOutputStats outputStats,
        Integer exitCode,
        ToolMetadata metadata,
        ApprovalRecord approval,
        String auditId
    ) {
        this(
            toolCallId, toolName, output,
            status.isFailure(),                                    // error → 从 status 派生
            toolError != null ? toolError.code() : null,           // errorCode
            durationMs,
            outputStats != null ? (int) outputStats.returnedBytes() : 0, // outputBytes
            outputStats != null && outputStats.truncated(),         // truncated
            status == ToolExecutionStatus.TIMED_OUT,                // timedOut
            exitCode,
            metadata,
            status, toolError, outputStats, approval, auditId,
            null, null, null, null                                  // V3 字段派生
        );
    }

    // ── 旧构造器（@Deprecated） ────────────────────────────────────

    /** @deprecated 使用 V2 规范构造器 */
    @Deprecated
    public ToolExecutionResult(
        String toolCallId,
        String toolName,
        String output,
        boolean error,
        String errorCode,
        long durationMs,
        int outputBytes,
        boolean truncated,
        boolean timedOut,
        Integer exitCode,
        ToolMetadata metadata
    ) {
        this(
            toolCallId, toolName, output,
            error, errorCode, durationMs, outputBytes, truncated, timedOut, exitCode, metadata,
            deriveStatus(error, timedOut, exitCode, errorCode),
            error ? new ToolError(errorCode != null ? errorCode : "TOOL_ERROR", output, false, java.util.Map.of()) : null,
            ToolOutputStats.fromOutput(output, truncated),
            null,  // approval
            null,  // auditId
            null, null, null, null // V3 字段派生
        );
    }

    // ── V2 工厂方法 ──────────────────────────────────────────────

    /** 成功结果 */
    public static ToolExecutionResult success(
            String toolCallId, String toolName, String output,
            long durationMs, ToolMetadata metadata) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        return new ToolExecutionResult(
            toolCallId, toolName, output,
            ToolExecutionStatus.SUCCESS, null, durationMs,
            new ToolOutputStats(bytes.length, bytes.length, false),
            null, metadata, null,
            UUID.randomUUID().toString()
        );
    }

    /** 成功结果（带截断信息） */
    public static ToolExecutionResult success(
            String toolCallId, String toolName, String output,
            long durationMs, ToolOutputStats outputStats, ToolMetadata metadata) {
        return new ToolExecutionResult(
            toolCallId, toolName, output,
            ToolExecutionStatus.SUCCESS, null, durationMs,
            outputStats, null, metadata, null,
            UUID.randomUUID().toString()
        );
    }

    /** 错误结果 */
    public static ToolExecutionResult error(
            String toolCallId, String toolName, String errorCode,
            String message, long durationMs, ToolMetadata metadata) {
        return new ToolExecutionResult(
            toolCallId, toolName, message,
            ToolExecutionStatus.TOOL_ERROR,
            ToolError.fatal(errorCode, message),
            durationMs,
            ToolOutputStats.fromOutput(message, false),
            null, metadata, null,
            UUID.randomUUID().toString()
        );
    }

    /** V2 带状态的错误结果 */
    public static ToolExecutionResult of(
            String toolCallId, String toolName, String output,
            ToolExecutionStatus status, ToolError toolError,
            long durationMs, ToolOutputStats outputStats,
            Integer exitCode, ToolMetadata metadata,
            ApprovalRecord approval) {
        return new ToolExecutionResult(
            toolCallId, toolName, output,
            status, toolError, durationMs,
            outputStats, exitCode, metadata, approval,
            UUID.randomUUID().toString()
        );
    }

    /** 工具未找到 */
    public static ToolExecutionResult notFound(
            String toolCallId, String toolName, long durationMs, ToolMetadata metadata) {
        return new ToolExecutionResult(
            toolCallId, toolName, "Tool not found: " + toolName,
            ToolExecutionStatus.NOT_FOUND,
            ToolError.fatal("NOT_FOUND", "Tool not found: " + toolName),
            durationMs,
            ToolOutputStats.EMPTY, null, metadata, null,
            UUID.randomUUID().toString()
        );
    }

    /** 被阻断 */
    public static ToolExecutionResult blocked(
            String toolCallId, String toolName, String reason,
            long durationMs, ToolMetadata metadata) {
        return new ToolExecutionResult(
            toolCallId, toolName, reason,
            ToolExecutionStatus.BLOCKED,
            ToolError.fatal("BLOCKED", reason),
            durationMs,
            ToolOutputStats.fromOutput(reason, false),
            null, metadata,
            ApprovalRecord.blockedByPolicy(reason),
            UUID.randomUUID().toString()
        );
    }

    /** 参数无效 */
    public static ToolExecutionResult invalidArguments(
            String toolCallId, String toolName, String reason,
            long durationMs, ToolMetadata metadata) {
        return new ToolExecutionResult(
            toolCallId, toolName, reason,
            ToolExecutionStatus.INVALID_ARGUMENTS,
            ToolError.fatal("INVALID_ARGUMENTS", reason),
            durationMs,
            ToolOutputStats.fromOutput(reason, false),
            null, metadata, null,
            UUID.randomUUID().toString()
        );
    }

    /** 超时 */
    public static ToolExecutionResult timedOut(
            String toolCallId, String toolName, String partialOutput,
            long durationMs, ToolOutputStats outputStats, ToolMetadata metadata) {
        return new ToolExecutionResult(
            toolCallId, toolName, partialOutput,
            ToolExecutionStatus.TIMED_OUT,
            ToolError.fatal("TIMED_OUT", "Execution timed out after " + durationMs + "ms"),
            durationMs,
            outputStats, null, metadata, null,
            UUID.randomUUID().toString()
        );
    }

    /** 内部错误 */
    public static ToolExecutionResult internalError(
            String toolCallId, String toolName, String message,
            long durationMs, ToolMetadata metadata) {
        return new ToolExecutionResult(
            toolCallId, toolName, message,
            ToolExecutionStatus.INTERNAL_ERROR,
            ToolError.fatal("INTERNAL_ERROR", message),
            durationMs,
            ToolOutputStats.fromOutput(message, false),
            null, metadata, null,
            UUID.randomUUID().toString()
        );
    }

    // ── V3 工厂与副本方法（P1-G） ─────────────────────────────────

    /** 取消结果：派发前取消 → NOT_DISPATCHED；执行中取消 → EFFECT_UNKNOWN。 */
    public static ToolExecutionResult cancelled(
            String toolCallId, String toolName, String reason,
            long durationMs, ToolMetadata metadata, boolean beforeDispatch) {
        FailureClass fc = beforeDispatch
            ? FailureClass.CANCELLED_BEFORE_DISPATCH
            : FailureClass.INTERRUPTED_OUTCOME_UNKNOWN;
        return new ToolExecutionResult(
            toolCallId, toolName, reason,
            ToolExecutionStatus.CANCELLED.isFailure(), "CANCELLED",
            durationMs, 0, false, false, null, metadata,
            ToolExecutionStatus.CANCELLED,
            ToolError.fatal("CANCELLED", reason),
            ToolOutputStats.fromOutput(reason, false), null,
            UUID.randomUUID().toString(),
            null, fc, null, null
        );
    }

    /** 替换可靠性维度（executor/attempt coordinator 使用）。 */
    public ToolExecutionResult withReliability(
            EffectCertainty certainty, FailureClass failure, String attempt) {
        return new ToolExecutionResult(
            toolCallId, toolName, output, error, errorCode, durationMs,
            outputBytes, truncated, timedOut, exitCode, metadata,
            status, toolError, outputStats, approval, auditId,
            certainty, failure, outputEnvelope, attempt
        );
    }

    /** 附加输出信封。 */
    public ToolExecutionResult withOutputEnvelope(OutputEnvelope envelope) {
        return new ToolExecutionResult(
            toolCallId, toolName, output, error, errorCode, durationMs,
            outputBytes, truncated, timedOut, exitCode, metadata,
            status, toolError, outputStats, approval, auditId,
            effectCertainty, failureClass, envelope, attemptId
        );
    }

    // ── 便利方法 ──────────────────────────────────────────────────

    /** 结果是否成功 */
    public boolean success() {
        return status != null ? status.isSuccess() : !error;
    }

    // ── 私有辅助 ──────────────────────────────────────────────────

    private static ToolExecutionStatus deriveStatus(
            boolean error, boolean timedOut, Integer exitCode, String errorCode) {
        if (timedOut) return ToolExecutionStatus.TIMED_OUT;
        if (error) {
            if ("REJECTED".equals(errorCode)) return ToolExecutionStatus.REJECTED;
            if ("PLAN_BLOCKED".equals(errorCode) || "SAFETY_BLOCKED".equals(errorCode))
                return ToolExecutionStatus.BLOCKED;
            if ("MODIFY_PARAMS".equals(errorCode)) return ToolExecutionStatus.INVALID_ARGUMENTS;
            if (exitCode != null && exitCode != 0) return ToolExecutionStatus.NON_ZERO_EXIT;
            return ToolExecutionStatus.TOOL_ERROR;
        }
        return ToolExecutionStatus.SUCCESS;
    }

    /** status → 确定性失败分类的保守派生（工具可显式给出更精确的分类）。 */
    private static FailureClass deriveFailureClass(ToolExecutionStatus status) {
        return switch (status) {
            case SUCCESS, VERIFICATION_PENDING -> FailureClass.NONE;
            case REJECTED -> FailureClass.APPROVAL_REJECTED;
            case BLOCKED -> FailureClass.PERMISSION_BLOCKED;
            case INVALID_ARGUMENTS -> FailureClass.INVALID_ARGUMENTS;
            case NOT_FOUND -> FailureClass.PRECONDITION_FAILED;
            case TIMED_OUT -> FailureClass.TIMEOUT_OUTCOME_UNKNOWN;
            case CANCELLED -> FailureClass.INTERRUPTED_OUTCOME_UNKNOWN;
            case NON_ZERO_EXIT, TOOL_ERROR -> FailureClass.EXECUTION_ERROR_OUTCOME_UNKNOWN;
            case INTERNAL_ERROR -> FailureClass.UNCLASSIFIED;
        };
    }

    /** 只读工具按契约无副作用；其余取失败分类的固有确定性，未知时保守 EFFECT_UNKNOWN。 */
    private static EffectCertainty deriveCertainty(FailureClass failureClass, ToolMetadata metadata) {
        if (metadata != null && metadata.isReadOnly()) {
            return EffectCertainty.NO_EFFECT_CONFIRMED;
        }
        if (failureClass != null) {
            return failureClass.certainty();
        }
        return EffectCertainty.EFFECT_UNKNOWN;
    }
}
