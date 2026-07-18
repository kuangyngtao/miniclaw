package com.clawkit.engine.impl;

import com.clawkit.engine.AgentState;
import com.clawkit.engine.AgentStateEvent;
import com.clawkit.engine.SubAgentCompleteEvent;
import com.clawkit.engine.SubAgentSpawnEvent;
import com.clawkit.engine.ToolEndEvent;
import com.clawkit.engine.ToolStartEvent;
import com.clawkit.observability.RunCompletedPayload;
import com.clawkit.observability.RunEventPayload;
import com.clawkit.observability.RunRecorder;
import com.clawkit.observability.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Single publisher for engine lifecycle, UI and recorder events. */
final class EngineEventHub {
    private static final Logger log = LoggerFactory.getLogger(EngineEventHub.class);
    private final List<RunRecorder> recorders = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> reasoning = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> thinkingTokens = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> tokens = new CopyOnWriteArrayList<>();
    private final List<Consumer<SubAgentSpawnEvent>> subAgentStarted = new CopyOnWriteArrayList<>();
    private final List<Consumer<SubAgentCompleteEvent>> subAgentCompleted = new CopyOnWriteArrayList<>();
    private final List<Consumer<ToolStartEvent>> toolStarted = new CopyOnWriteArrayList<>();
    private final List<Consumer<ToolEndEvent>> toolCompleted = new CopyOnWriteArrayList<>();
    private final List<Consumer<AgentStateEvent>> states = new CopyOnWriteArrayList<>();
    private final AtomicBoolean completionSent = new AtomicBoolean();
    private volatile Runnable thinkingBegin;

    EngineEventHub(RunRecorder recorder) { addRecorder(recorder); }

    void resetRun() { completionSent.set(false); }
    void addRecorder(RunRecorder recorder) { if (recorder != null) recorders.add(recorder); }
    void removeRecorder(RunRecorder recorder) { if (recorder != null) recorders.remove(recorder); }
    RunRecorder recorder() { return this::record; }

    void record(RunEventPayload payload, String runId, String parentRunId,
                Integer turn, Instant time) {
        for (RunRecorder recorder : recorders) {
            try { recorder.record(payload, runId, parentRunId, turn, time); }
            catch (Exception ignored) {}
        }
    }

    void complete(RunStatus status, String code, String message,
                  String runId, String parentRunId) {
        if (completionSent.compareAndSet(false, true)) {
            record(new RunCompletedPayload(status, code, message), runId, parentRunId,
                null, Instant.now());
        }
    }

    void state(AgentState state, int turn, Map<String, Object> metadata) {
        var event = new AgentStateEvent(state, turn, metadata);
        states.forEach(listener -> safe(() -> listener.accept(event), "state"));
    }

    void subAgentStarted(SubAgentSpawnEvent event) {
        subAgentStarted.forEach(listener -> safe(() -> listener.accept(event), "subagent-start"));
    }
    void subAgentCompleted(SubAgentCompleteEvent event) {
        subAgentCompleted.forEach(listener -> safe(() -> listener.accept(event), "subagent-complete"));
    }
    void toolStarted(ToolStartEvent event) {
        toolStarted.forEach(listener -> safe(() -> listener.accept(event), "tool-start"));
    }
    void toolCompleted(ToolEndEvent event) {
        toolCompleted.forEach(listener -> safe(() -> listener.accept(event), "tool-complete"));
    }
    void reasoning(String value) {
        reasoning.forEach(listener -> safe(() -> listener.accept(value), "reasoning"));
    }
    void thinkingBegin() { if (thinkingBegin != null) safe(thinkingBegin, "thinking-begin"); }

    void setThinkingBegin(Runnable callback) { thinkingBegin = callback; }
    void onReasoning(Consumer<String> listener) { if (listener != null) reasoning.add(listener); }
    void onThinkingToken(Consumer<String> listener) { if (listener != null) thinkingTokens.add(listener); }
    void onSubAgentStarted(Consumer<SubAgentSpawnEvent> listener) { if (listener != null) subAgentStarted.add(listener); }
    void onSubAgentCompleted(Consumer<SubAgentCompleteEvent> listener) { if (listener != null) subAgentCompleted.add(listener); }
    void onToolStarted(Consumer<ToolStartEvent> listener) { if (listener != null) toolStarted.add(listener); }
    void onToolCompleted(Consumer<ToolEndEvent> listener) { if (listener != null) toolCompleted.add(listener); }
    void onState(Consumer<AgentStateEvent> listener) { if (listener != null) states.add(listener); }
    void removeState(Consumer<AgentStateEvent> listener) { if (listener != null) states.remove(listener); }
    void setToken(Consumer<String> listener) { tokens.clear(); if (listener != null) tokens.add(listener); }
    void addToken(Consumer<String> listener) { if (listener != null) tokens.add(listener); }
    void removeToken(Consumer<String> listener) { if (listener != null) tokens.remove(listener); }

    private void safe(Runnable action, String kind) {
        try { action.run(); }
        catch (Exception e) { log.warn("[EngineEvents] {} listener failed: {}", kind, e.getMessage()); }
    }
}
