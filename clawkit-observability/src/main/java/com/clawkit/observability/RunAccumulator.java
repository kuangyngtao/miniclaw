package com.clawkit.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 RunEventEnvelope 流式聚合 RunSummary 和 RunMetrics。
 *
 * <p>用法：
 * <pre>{@code
 * var acc = new RunAccumulator(runId);
 * acc.accept(env1);
 * acc.accept(env2);
 * RunSummary summary = acc.snapshot();
 * RunMetrics metrics = acc.metrics();
 * }</pre>
 *
 * <p>实时 recorder 和批量投影共用同一套 accept() 逻辑，
 * 保证 summary.json 与 /metrics 结果一致。
 */
public class RunAccumulator {

    private final String runId;
    private final List<String> warnings = new ArrayList<>();

    // ── 聚合状态 ─────────────────────────────────────────────────────

    private String parentRunId;
    private String taskSummary;
    private RunStatus status = RunStatus.RUNNING;
    private Instant startTime;
    private Instant endTime;

    private int turns;

    private int providerCalls;
    private int providerFailures;
    private int providerRetries;
    private long providerDurationMs;
    private long inputTokens;
    private long outputTokens;
    private boolean tokensEstimated;

    private int toolCalls;
    private int toolFailures;
    private int lowRiskToolCalls;
    private int mediumRiskToolCalls;
    private int highRiskToolCalls;

    private int approvalRequested;
    private int approvalApproved;
    private int approvalRejected;
    private int approvalModified;

    private int contextPeakTokens;
    private int contextWindow;
    private int compactCount;
    private int compactFailures;

    private String permissionMode = "";
    private String thinkingMode = "";
    private String executionMode = "";
    private String workDir = "";
    private String model = "";

    private String errorCode;
    private String errorMessage;

    public RunAccumulator(String runId) {
        this.runId = runId;
    }

    // ── 聚合入口 ─────────────────────────────────────────────────────

    /** 接收一个 envelope 并更新聚合状态 */
    public void accept(RunEventEnvelope env) {
        switch (env.payload()) {
            case RunStartedPayload p     -> onRunStarted(env, p);
            case RunCompletedPayload p   -> onRunCompleted(env, p);
            case TurnStartedPayload p    -> { /* marker */ }
            case TurnCompletedPayload p  -> onTurnCompleted(env, p);
            case ContextPreparedPayload p -> onContextPrepared(p);
            case ProviderCallStartedPayload p -> { /* marker */ }
            case ProviderCallCompletedPayload p -> onProviderCompleted(p);
            case ToolInvokedPayload p    -> onToolInvoked(p);
            case ToolCompletedPayload p  -> onToolCompleted(p);
            case ApprovalDecidedPayload p -> onApprovalDecided(p);
            case CompactTriggeredPayload p -> { /* marker */ }
            case CompactCompletedPayload p -> onCompactCompleted(p);
            default -> { /* UnknownEvent — skip */ }
        }
    }

    // ── 各事件处理 ───────────────────────────────────────────────────

    private void onRunStarted(RunEventEnvelope env, RunStartedPayload p) {
        this.parentRunId = env.parentRunId();
        this.taskSummary = p.taskSummary();
        this.workDir = p.workDir();
        this.model = p.model();
        this.permissionMode = p.permissionMode();
        this.thinkingMode = p.thinkingMode();
        this.executionMode = p.executionMode();
        this.startTime = env.occurredAt();
        this.status = RunStatus.RUNNING;
    }

    private void onRunCompleted(RunEventEnvelope env, RunCompletedPayload p) {
        this.status = p.status();
        this.errorCode = p.errorCode();
        this.errorMessage = p.errorMessage();
        this.endTime = env.occurredAt();
    }

    private void onTurnCompleted(RunEventEnvelope env, TurnCompletedPayload p) {
        this.turns = env.turnNumber() != null ? Math.max(this.turns, env.turnNumber()) : this.turns + 1;
    }

    private void onContextPrepared(ContextPreparedPayload p) {
        this.contextPeakTokens = Math.max(this.contextPeakTokens, p.totalTokens());
        this.contextWindow = Math.max(this.contextWindow, p.contextWindow());
    }

    private void onProviderCompleted(ProviderCallCompletedPayload p) {
        this.providerCalls++;
        if (p.failed()) this.providerFailures++;
        this.providerRetries += p.retryCount();
        this.providerDurationMs += p.durationMs();
        this.inputTokens += p.inputTokens();
        this.outputTokens += p.outputTokens();
        if (p.tokensEstimated()) this.tokensEstimated = true;
    }

    private void onToolInvoked(ToolInvokedPayload p) {
        this.toolCalls++;
        switch (p.riskLevel()) {
            case LOW    -> this.lowRiskToolCalls++;
            case MEDIUM -> this.mediumRiskToolCalls++;
            case HIGH   -> this.highRiskToolCalls++;
        }
    }

    private void onToolCompleted(ToolCompletedPayload p) {
        if (!p.success()) this.toolFailures++;
    }

    private void onApprovalDecided(ApprovalDecidedPayload p) {
        this.approvalRequested++;
        switch (p.decision()) {
            case "APPROVE", "APPROVE_SAME_TYPE", "AUTO_APPROVED" -> this.approvalApproved++;
            case "REJECT", "SAFETY_BLOCKED", "PLAN_BLOCKED" -> this.approvalRejected++;
            case "MODIFY" -> this.approvalModified++;
            // NOT_REQUIRED: counted in requested but not in approved/rejected/modified
            default -> { /* no-op */ }
        }
    }

    private void onCompactCompleted(CompactCompletedPayload p) {
        this.compactCount++;
        if (p.failed()) this.compactFailures++;
    }

    /** 添加 warning（run 关闭不完整等场景） */
    public void addWarning(String warning) {
        if (warning != null && !warning.isBlank()) {
            this.warnings.add(warning);
        }
    }

    // ── 输出 ──────────────────────────────────────────────────────────

    /** 生成当前快照（用于 summary.json） */
    public RunSummary snapshot() {
        long durationMs = (startTime != null && endTime != null)
            ? Duration.between(startTime, endTime).toMillis()
            : 0;

        return new RunSummary(
            RunSummary.CURRENT_SCHEMA_VERSION,
            runId,
            parentRunId,
            taskSummary,
            status,
            status != RunStatus.RUNNING,
            startTime,
            endTime,
            durationMs,
            turns,
            providerCalls,
            providerFailures,
            providerRetries,
            providerDurationMs,
            inputTokens,
            outputTokens,
            tokensEstimated,
            toolCalls,
            toolFailures,
            lowRiskToolCalls,
            mediumRiskToolCalls,
            highRiskToolCalls,
            approvalRequested,
            approvalApproved,
            approvalRejected,
            approvalModified,
            contextPeakTokens,
            contextWindow,
            compactCount,
            compactFailures,
            permissionMode,
            thinkingMode,
            executionMode,
            workDir,
            model,
            errorCode,
            errorMessage,
            List.copyOf(warnings)
        );
    }

    /** 生成完整指标投影（用于 /metrics 命令） */
    public RunMetrics metrics() {
        RunSummary s = snapshot();
        return new RunMetrics(
            runId,
            s,
            new RunMetrics.ProviderMetrics(
                providerCalls, providerFailures, providerRetries,
                providerDurationMs, inputTokens, outputTokens, tokensEstimated),
            new RunMetrics.ToolMetrics(
                toolCalls, toolFailures,
                lowRiskToolCalls, mediumRiskToolCalls, highRiskToolCalls),
            new RunMetrics.ContextMetrics(
                contextPeakTokens, contextWindow, compactCount, compactFailures),
            new RunMetrics.CompactMetrics(compactCount, compactFailures),
            new RunMetrics.ApprovalMetrics(
                approvalRequested, approvalApproved, approvalRejected, approvalModified),
            List.copyOf(warnings)
        );
    }
}
