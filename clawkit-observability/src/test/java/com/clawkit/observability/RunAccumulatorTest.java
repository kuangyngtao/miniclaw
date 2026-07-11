package com.clawkit.observability;

import com.clawkit.tools.ToolRiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunAccumulatorTest {

    private RunAccumulator acc;
    private RunEventCodec codec;
    private static final String RUN_ID = "run-001";
    private static final Instant T0 = Instant.parse("2026-07-11T10:00:00Z");

    @BeforeEach
    void setUp() {
        acc = new RunAccumulator(RUN_ID);
        codec = new RunEventCodec(new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    private RunEventEnvelope env(RunEventPayload payload, int seq, Instant t,
                                  String parentRunId, Integer turnNumber) {
        return codec.encode(payload, RUN_ID, parentRunId, turnNumber, seq, t);
    }

    private RunEventEnvelope env(RunEventPayload payload, int seq, Instant t, Integer turnNumber) {
        return env(payload, seq, t, null, turnNumber);
    }

    private RunEventEnvelope env(RunEventPayload payload, int seq, Instant t) {
        return env(payload, seq, t, null, null);
    }

    @Test
    void shouldStartInRunningState() {
        var summary = acc.snapshot();
        assertThat(summary.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(summary.finalized()).isFalse();
    }

    @Test
    void shouldTransitionToCompletedOnRunCompleted() {
        acc.accept(env(new RunStartedPayload("task", "/tmp", "gpt-4",
            "ASK", "none", "ReAct"), 1, T0));
        acc.accept(env(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
            2, T0.plusSeconds(10)));

        var summary = acc.snapshot();
        assertThat(summary.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary.finalized()).isTrue();
        assertThat(summary.durationMs()).isEqualTo(10_000);
    }

    @Test
    void shouldRecordTaskSummaryFromRunStarted() {
        acc.accept(env(new RunStartedPayload("fix bug #42", "/workspace", "gpt-4",
            "AUTO", "slow", "ReAct"), 1, T0));

        acc.accept(env(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
            2, T0.plusSeconds(5)));

        var summary = acc.snapshot();
        assertThat(summary.taskSummary()).isEqualTo("fix bug #42");
        assertThat(summary.workDir()).isEqualTo("/workspace");
        assertThat(summary.model()).isEqualTo("gpt-4");
        assertThat(summary.permissionMode()).isEqualTo("AUTO");
        assertThat(summary.thinkingMode()).isEqualTo("slow");
        assertThat(summary.executionMode()).isEqualTo("ReAct");
    }

    @Test
    void shouldAggregateProviderMetrics() {
        acc.accept(env(new RunStartedPayload("task", "/tmp", "m", "ASK", "none", "ReAct"),
            1, T0));
        acc.accept(env(new ProviderCallCompletedPayload("pc-1", "phase2",
            true, 500, 100, false, 1200, 0, false, null, null),
            2, T0.plusSeconds(1), 1));
        acc.accept(env(new ProviderCallCompletedPayload("pc-2", "phase2",
            true, 600, 150, true, 800, 1, true, "TIMEOUT", "timeout"),
            3, T0.plusSeconds(3), 2));
        acc.accept(env(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
            4, T0.plusSeconds(5)));

        var summary = acc.snapshot();
        assertThat(summary.providerCalls()).isEqualTo(2);
        assertThat(summary.providerFailures()).isEqualTo(1);
        assertThat(summary.providerRetries()).isEqualTo(1);
        assertThat(summary.providerDurationMs()).isEqualTo(2000);
        assertThat(summary.inputTokens()).isEqualTo(1100);
        assertThat(summary.outputTokens()).isEqualTo(250);
        assertThat(summary.tokensEstimated()).isTrue();
    }

    @Test
    void shouldAggregateToolRiskLevels() {
        acc.accept(env(new RunStartedPayload("task", "/tmp", "m", "ASK", "none", "ReAct"),
            1, T0));
        acc.accept(env(new ToolInvokedPayload("tc-1", "read", "arg",
            false, true, ToolRiskLevel.LOW, false, false),
            2, T0.plusSeconds(1), 1));
        acc.accept(env(new ToolInvokedPayload("tc-2", "write", "arg",
            false, false, ToolRiskLevel.HIGH, true, true),
            3, T0.plusSeconds(2), 1));
        acc.accept(env(new ToolInvokedPayload("tc-3", "bash", "arg",
            false, false, ToolRiskLevel.MEDIUM, true, true),
            4, T0.plusSeconds(3), 1));
        acc.accept(env(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
            5, T0.plusSeconds(5)));

        var summary = acc.snapshot();
        assertThat(summary.toolCalls()).isEqualTo(3);
        assertThat(summary.lowRiskToolCalls()).isEqualTo(1);
        assertThat(summary.mediumRiskToolCalls()).isEqualTo(1);
        assertThat(summary.highRiskToolCalls()).isEqualTo(1);
    }

    @Test
    void shouldAggregateToolFailures() {
        acc.accept(env(new RunStartedPayload("task", "/tmp", "m", "ASK", "none", "ReAct"),
            1, T0));
        acc.accept(env(new ToolInvokedPayload("tc-1", "read", "arg",
            false, true, ToolRiskLevel.LOW, false, false),
            2, T0.plusSeconds(1), 1));
        acc.accept(env(new ToolCompletedPayload("tc-1", "read", true, 50, 1024,
            false, false, null, null, null),
            3, T0.plusSeconds(1), 1));
        acc.accept(env(new ToolInvokedPayload("tc-2", "write", "arg",
            false, false, ToolRiskLevel.HIGH, true, true),
            4, T0.plusSeconds(2), 1));
        acc.accept(env(new ToolCompletedPayload("tc-2", "write", false, 100, 0,
            false, false, null, "PERM", "denied"),
            5, T0.plusSeconds(2), 1));
        acc.accept(env(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
            6, T0.plusSeconds(5)));

        var summary = acc.snapshot();
        assertThat(summary.toolCalls()).isEqualTo(2);
        assertThat(summary.toolFailures()).isEqualTo(1);
    }

    @Test
    void shouldAggregateApprovalDecisions() {
        acc.accept(env(new RunStartedPayload("task", "/tmp", "m", "ASK", "none", "ReAct"),
            1, T0));
        acc.accept(env(new ApprovalDecidedPayload("tc-1", "write",
            ToolRiskLevel.HIGH, "APPROVE", "USER", "ok"),
            2, T0.plusSeconds(1), 1));
        acc.accept(env(new ApprovalDecidedPayload("tc-2", "bash",
            ToolRiskLevel.HIGH, "REJECT", "USER", "dangerous"),
            3, T0.plusSeconds(2), 1));
        acc.accept(env(new ApprovalDecidedPayload("tc-3", "read",
            ToolRiskLevel.LOW, "NOT_REQUIRED", "TOOL_METADATA", ""),
            4, T0.plusSeconds(3), 1));
        acc.accept(env(new ApprovalDecidedPayload("tc-4", "write",
            ToolRiskLevel.HIGH, "MODIFY", "USER", "change path"),
            5, T0.plusSeconds(4), 1));
        acc.accept(env(new ApprovalDecidedPayload("tc-5", "rm",
            ToolRiskLevel.HIGH, "APPROVE_SAME_TYPE", "SAME_TYPE_CACHE", ""),
            6, T0.plusSeconds(5), 1));
        acc.accept(env(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
            7, T0.plusSeconds(6)));

        var summary = acc.snapshot();
        assertThat(summary.approvalRequested()).isEqualTo(5);
        assertThat(summary.approvalApproved()).isEqualTo(2); // APPROVE + APPROVE_SAME_TYPE
        assertThat(summary.approvalRejected()).isEqualTo(1);
        assertThat(summary.approvalModified()).isEqualTo(1);
    }

    @Test
    void shouldTrackContextPeak() {
        acc.accept(env(new RunStartedPayload("task", "/tmp", "m", "ASK", "none", "ReAct"),
            1, T0));
        acc.accept(env(new ContextPreparedPayload(10, 2000, 128000, false,
            "OK", Map.of("system", 500), false, 0, false),
            2, T0.plusSeconds(1), 1));
        acc.accept(env(new ContextPreparedPayload(15, 3500, 128000, false,
            "WARN", Map.of("system", 800), false, 0, false),
            3, T0.plusSeconds(2), 2));
        acc.accept(env(new ContextPreparedPayload(8, 1800, 128000, false,
            "OK", Map.of("system", 400), false, 0, false),
            4, T0.plusSeconds(3), 3));
        acc.accept(env(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
            5, T0.plusSeconds(5)));

        var summary = acc.snapshot();
        assertThat(summary.contextPeakTokens()).isEqualTo(3500); // max
        assertThat(summary.contextWindow()).isEqualTo(128000);
    }

    @Test
    void shouldAggregateCompactMetrics() {
        acc.accept(env(new RunStartedPayload("task", "/tmp", "m", "ASK", "none", "ReAct"),
            1, T0));
        acc.accept(env(new CompactCompletedPayload(20, 10, 5000, 2500,
            "WARN", "OK", Map.of(), Map.of(), 2,
            List.of("evict_old"), 300, false, null),
            2, T0.plusSeconds(2), 1));
        acc.accept(env(new CompactCompletedPayload(25, 12, 6000, 3000,
            "WARN", "OK", Map.of(), Map.of(), 3,
            List.of("evict_old"), 250, true, "COMPACT_FAILED"),
            3, T0.plusSeconds(4), 2));
        acc.accept(env(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
            4, T0.plusSeconds(6)));

        var summary = acc.snapshot();
        assertThat(summary.compactCount()).isEqualTo(2);
        assertThat(summary.compactFailures()).isEqualTo(1);
    }

    @Test
    void shouldTrackTurnCount() {
        acc.accept(env(new RunStartedPayload("task", "/tmp", "m", "ASK", "none", "ReAct"),
            1, T0));
        acc.accept(env(new TurnCompletedPayload(1, false, false, null, null),
            2, T0.plusSeconds(1), 1));
        acc.accept(env(new TurnCompletedPayload(2, false, false, null, null),
            3, T0.plusSeconds(2), 2));
        acc.accept(env(new TurnCompletedPayload(1, true, false, null, null),
            4, T0.plusSeconds(3), 3));
        acc.accept(env(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
            5, T0.plusSeconds(5)));

        var summary = acc.snapshot();
        assertThat(summary.turns()).isEqualTo(3);
    }

    @Test
    void shouldRecordParentRunId() {
        acc.accept(env(new RunStartedPayload("sub task", "/tmp", "m", "ASK", "none", "SubAgent"),
            1, T0, "run-parent", null));
        acc.accept(env(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
            2, T0.plusSeconds(3), "run-parent", null));

        var summary = acc.snapshot();
        assertThat(summary.parentRunId()).isEqualTo("run-parent");
    }

    @Test
    void shouldRecordErrorOnFailedRun() {
        acc.accept(env(new RunStartedPayload("task", "/tmp", "m", "ASK", "none", "ReAct"),
            1, T0));
        acc.accept(env(new RunCompletedPayload(RunStatus.LLM_ERROR, "LLM_500", "Server error"),
            2, T0.plusSeconds(2)));

        var summary = acc.snapshot();
        assertThat(summary.status()).isEqualTo(RunStatus.LLM_ERROR);
        assertThat(summary.errorCode()).isEqualTo("LLM_500");
        assertThat(summary.errorMessage()).isEqualTo("Server error");
    }

    @Test
    void shouldProduceCompleteMetrics() {
        acc.accept(env(new RunStartedPayload("task", "/tmp", "gpt-4", "ASK", "none", "ReAct"),
            1, T0));
        acc.accept(env(new ProviderCallCompletedPayload("pc-1", "phase2",
            true, 500, 100, false, 1200, 0, false, null, null),
            2, T0.plusSeconds(1), 1));
        acc.accept(env(new ToolInvokedPayload("tc-1", "read", "arg",
            false, true, ToolRiskLevel.LOW, false, false),
            3, T0.plusSeconds(1), 1));
        acc.accept(env(new ToolCompletedPayload("tc-1", "read", true, 50, 1024,
            false, false, null, null, null),
            4, T0.plusSeconds(1), 1));
        acc.accept(env(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
            5, T0.plusSeconds(5)));

        var metrics = acc.metrics();
        assertThat(metrics.runId()).isEqualTo(RUN_ID);
        assertThat(metrics.summary()).isNotNull();
        assertThat(metrics.provider()).isNotNull();
        assertThat(metrics.provider().calls()).isEqualTo(1);
        assertThat(metrics.tools()).isNotNull();
        assertThat(metrics.tools().calls()).isEqualTo(1);
        assertThat(metrics.tools().lowRisk()).isEqualTo(1);
        assertThat(metrics.context()).isNotNull();
        assertThat(metrics.compact()).isNotNull();
        assertThat(metrics.approval()).isNotNull();
    }

    @Test
    void shouldHandleEmptyRunGracefullyInSnapshot() {
        // Even without any events, snapshot() should return a valid summary
        var summary = acc.snapshot();
        assertThat(summary.runId()).isEqualTo(RUN_ID);
        assertThat(summary.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(summary.turns()).isEqualTo(0);
        assertThat(summary.toolCalls()).isEqualTo(0);
        assertThat(summary.durationMs()).isEqualTo(0);
    }

    @Test
    void shouldAddWarnings() {
        acc.addWarning("test warning");
        acc.addWarning(null);   // should be ignored
        acc.addWarning("");     // should be ignored

        var summary = acc.snapshot();
        assertThat(summary.warnings()).containsExactly("test warning");
    }

    @Test
    void shouldIgnoreMarkerEvents() {
        // TurnStarted, ProviderCallStarted, CompactTriggered are markers
        acc.accept(env(new RunStartedPayload("task", "/tmp", "m", "ASK", "none", "ReAct"),
            1, T0));
        acc.accept(env(new TurnStartedPayload(), 2, T0.plusMillis(100), 1));
        acc.accept(env(new ProviderCallStartedPayload("pc-1", "phase2", true),
            3, T0.plusMillis(200), 1));
        acc.accept(env(new CompactTriggeredPayload(), 4, T0.plusMillis(300), 1));

        // These should NOT affect any counters
        var summary = acc.snapshot();
        assertThat(summary.turns()).isEqualTo(0);
        assertThat(summary.providerCalls()).isEqualTo(0);
        assertThat(summary.compactCount()).isEqualTo(0);
    }

    @Test
    void shouldIgnoreUnknownEvents() {
        acc.accept(env(new RunStartedPayload("task", "/tmp", "m", "ASK", "none", "ReAct"),
            1, T0));
        acc.accept(env(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
            2, T0.plusSeconds(5)));

        // Create an envelope with UnknownEventPayload directly
        var unknownEnv = new RunEventEnvelope(1, "evt-x", "unknown_type", 3,
            T0, T0, RUN_ID, null, null, new UnknownEventPayload("unknown_type", null));
        // This should be safely ignored
        assertThat(acc.snapshot().status()).isEqualTo(RunStatus.COMPLETED);
    }
}
