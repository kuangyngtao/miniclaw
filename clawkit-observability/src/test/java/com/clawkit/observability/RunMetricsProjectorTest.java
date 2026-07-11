package com.clawkit.observability;

import com.clawkit.tools.ToolRiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunMetricsProjectorTest {

    private RunEventCodec codec;
    private static final String RUN_ID = "run-001";
    private static final Instant T0 = Instant.parse("2026-07-11T10:00:00Z");

    @BeforeEach
    void setUp() {
        codec = new RunEventCodec(new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    private RunEventEnvelope env(RunEventPayload payload, int seq, Instant t, Integer turnNumber) {
        return codec.encode(payload, RUN_ID, null, turnNumber, seq, t);
    }

    @Test
    void shouldProjectMetricsFromCompleteEventList() {
        var events = List.of(
            env(new RunStartedPayload("analyze code", "/workspace", "gpt-4",
                "ASK", "none", "ReAct"), 1, T0, null),
            env(new ProviderCallCompletedPayload("pc-1", "phase2",
                true, 500, 100, false, 1200, 0, false, null, null),
                2, T0.plusSeconds(1), 1),
            env(new ToolInvokedPayload("tc-1", "read", "arg",
                false, true, ToolRiskLevel.LOW, false, false),
                3, T0.plusSeconds(1), 1),
            env(new ToolCompletedPayload("tc-1", "read", true, 50, 1024,
                false, false, null, null, null),
                4, T0.plusSeconds(1), 1),
            env(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
                5, T0.plusSeconds(5), null)
        );

        var metrics = RunMetricsProjector.project(events);

        assertThat(metrics.runId()).isEqualTo(RUN_ID);
        assertThat(metrics.summary().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(metrics.provider().calls()).isEqualTo(1);
        assertThat(metrics.tools().calls()).isEqualTo(1);
    }

    @Test
    void shouldProjectSameSummaryForLiveAndReplay() {
        // Live: accept events one by one
        var liveAcc = new RunAccumulator(RUN_ID);
        liveAcc.accept(env(new RunStartedPayload("task", "/tmp", "gpt-4",
            "ASK", "none", "ReAct"), 1, T0, null));
        liveAcc.accept(env(new ProviderCallCompletedPayload("pc-1", "phase2",
            true, 500, 100, false, 1200, 0, false, null, null),
            2, T0.plusSeconds(1), 1));
        liveAcc.accept(env(new ToolInvokedPayload("tc-1", "read", "arg",
            false, true, ToolRiskLevel.LOW, false, false),
            3, T0.plusSeconds(1), 1));
        liveAcc.accept(env(new ToolCompletedPayload("tc-1", "read", true, 50, 1024,
            false, false, null, null, null),
            4, T0.plusSeconds(1), 1));
        liveAcc.accept(env(new ToolInvokedPayload("tc-2", "write", "arg",
            false, false, ToolRiskLevel.HIGH, true, true),
            5, T0.plusSeconds(2), 1));
        liveAcc.accept(env(new ToolCompletedPayload("tc-2", "write", false, 100, 0,
            false, false, null, "PERM", "denied"),
            6, T0.plusSeconds(2), 1));
        liveAcc.accept(env(new ApprovalDecidedPayload("tc-2", "write",
            ToolRiskLevel.HIGH, "REJECT", "USER", "dangerous"),
            7, T0.plusSeconds(2), 1));
        liveAcc.accept(env(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
            8, T0.plusSeconds(5), null));

        var liveSummary = liveAcc.snapshot();

        // Replay: project from the same events
        var events = List.of(
            env(new RunStartedPayload("task", "/tmp", "gpt-4",
                "ASK", "none", "ReAct"), 1, T0, null),
            env(new ProviderCallCompletedPayload("pc-1", "phase2",
                true, 500, 100, false, 1200, 0, false, null, null),
                2, T0.plusSeconds(1), 1),
            env(new ToolInvokedPayload("tc-1", "read", "arg",
                false, true, ToolRiskLevel.LOW, false, false),
                3, T0.plusSeconds(1), 1),
            env(new ToolCompletedPayload("tc-1", "read", true, 50, 1024,
                false, false, null, null, null),
                4, T0.plusSeconds(1), 1),
            env(new ToolInvokedPayload("tc-2", "write", "arg",
                false, false, ToolRiskLevel.HIGH, true, true),
                5, T0.plusSeconds(2), 1),
            env(new ToolCompletedPayload("tc-2", "write", false, 100, 0,
                false, false, null, "PERM", "denied"),
                6, T0.plusSeconds(2), 1),
            env(new ApprovalDecidedPayload("tc-2", "write",
                ToolRiskLevel.HIGH, "REJECT", "USER", "dangerous"),
                7, T0.plusSeconds(2), 1),
            env(new RunCompletedPayload(RunStatus.COMPLETED, null, null),
                8, T0.plusSeconds(5), null)
        );
        var replaySummary = RunMetricsProjector.projectSummary(events);

        // Every field must match
        assertThat(replaySummary.runId()).isEqualTo(liveSummary.runId());
        assertThat(replaySummary.status()).isEqualTo(liveSummary.status());
        assertThat(replaySummary.turns()).isEqualTo(liveSummary.turns());
        assertThat(replaySummary.toolCalls()).isEqualTo(liveSummary.toolCalls());
        assertThat(replaySummary.toolFailures()).isEqualTo(liveSummary.toolFailures());
        assertThat(replaySummary.lowRiskToolCalls()).isEqualTo(liveSummary.lowRiskToolCalls());
        assertThat(replaySummary.highRiskToolCalls()).isEqualTo(liveSummary.highRiskToolCalls());
        assertThat(replaySummary.providerCalls()).isEqualTo(liveSummary.providerCalls());
        assertThat(replaySummary.providerFailures()).isEqualTo(liveSummary.providerFailures());
        assertThat(replaySummary.inputTokens()).isEqualTo(liveSummary.inputTokens());
        assertThat(replaySummary.outputTokens()).isEqualTo(liveSummary.outputTokens());
        assertThat(replaySummary.providerDurationMs()).isEqualTo(liveSummary.providerDurationMs());
        assertThat(replaySummary.approvalRequested()).isEqualTo(liveSummary.approvalRequested());
        assertThat(replaySummary.approvalRejected()).isEqualTo(liveSummary.approvalRejected());
        assertThat(replaySummary.taskSummary()).isEqualTo(liveSummary.taskSummary());
        assertThat(replaySummary.model()).isEqualTo(liveSummary.model());
    }

    @Test
    void shouldRejectEmptyEventList() {
        assertThrows(IllegalArgumentException.class, () ->
            RunMetricsProjector.project(List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            RunMetricsProjector.project(null));
    }

    @Test
    void shouldRejectEmptyEventListForSummary() {
        assertThrows(IllegalArgumentException.class, () ->
            RunMetricsProjector.projectSummary(List.of()));
    }
}
