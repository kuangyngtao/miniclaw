package com.clawkit.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

/**
 * 基于本地文件的 RunRecorder 实现。
 *
 * <p>每个 run 在 .clawkit/runs/&lt;run-id&gt;/ 下写入：
 * <ul>
 *   <li>events.jsonl — 所有事件，每行一个 RunEventEnvelope JSON</li>
 *   <li>summary.json — RunSummary 原子快照</li>
 * </ul>
 *
 * <p>线程安全：不同 run 使用独立的 writer、lock 和 accumulator，可并行写入。
 *
 * <p>实现 AutoCloseable：关闭时标记所有活跃 run 为 INCOMPLETE。
 */
public class FileRunRecorder implements RunRecorder, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileRunRecorder.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String RUN_ID_REGEX = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";

    private final Path runsDir;
    private final RunEventCodec codec;
    private final AtomicJsonWriter atomicWriter;
    private final ConcurrentHashMap<String, RunState> activeRuns = new ConcurrentHashMap<>();

    public FileRunRecorder(Path baseDir) {
        this.runsDir = baseDir.resolve("runs");
        this.codec = new RunEventCodec(MAPPER);
        this.atomicWriter = new AtomicJsonWriter(MAPPER);
        try {
            Files.createDirectories(runsDir);
        } catch (IOException e) {
            log.error("无法创建 runs 目录: {}", runsDir, e);
        }
    }

    public FileRunRecorder() {
        this(Path.of(System.getProperty("user.home"), ".clawkit"));
    }

    // ── RunRecorder ────────────────────────────────────────────────

    @Override
    public void record(RunEventPayload payload, String runId, String parentRunId,
                        Integer turnNumber, Instant occurredAt) {
        try {
            dispatch(payload, runId, parentRunId, turnNumber, occurredAt);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("FileRunRecorder 写入失败: runId={}, type={}", runId,
                payload.getClass().getSimpleName(), e);
        }
    }

    private void dispatch(RunEventPayload payload, String runId, String parentRunId,
                           Integer turnNumber, Instant occurredAt) throws IOException {
        switch (payload) {
            case RunStartedPayload p -> handleRunStarted(p, runId, parentRunId, occurredAt);
            case RunCompletedPayload p -> handleRunCompleted(p, runId, occurredAt);
            default -> handleRegularEvent(payload, runId, parentRunId, turnNumber, occurredAt);
        }
    }

    // ── Run lifecycle ──────────────────────────────────────────────

    private void handleRunStarted(RunStartedPayload p, String runId,
                                   String parentRunId, Instant occurredAt) throws IOException {
        validateRunId(runId);

        if (activeRuns.containsKey(runId)) {
            log.warn("重复的 RunStarted: runId={}，忽略", runId);
            return;
        }

        Path runDir = runsDir.resolve(runId).normalize();
        if (!runDir.startsWith(runsDir)) {
            throw new IllegalArgumentException("Invalid runId (路径逃逸): " + runId);
        }
        Files.createDirectories(runDir);

        BufferedWriter writer = Files.newBufferedWriter(
            runDir.resolve("events.jsonl"), CREATE, APPEND);

        RunState state = new RunState(runId, runDir, writer);
        state.parentRunId = parentRunId;
        state.startTime = occurredAt;

        writeEvent(state, p, null, occurredAt);

        var summary = state.accumulator.snapshot();
        atomicWriter.write(runDir.resolve("summary.json"), summary);

        RunState previous = activeRuns.putIfAbsent(runId, state);
        if (previous != null) {
            writer.close();
            log.warn("并发 RunStarted 竞争: runId={}，使用已有状态", runId);
        }
    }

    private void handleRunCompleted(RunCompletedPayload p, String runId,
                                     Instant occurredAt) throws IOException {
        RunState state = activeRuns.get(runId);
        if (state == null) {
            log.warn("未找到对应 run 的 RunCompleted: runId={}", runId);
            return;
        }

        state.lock.lock();
        try {
            if (state.completed) {
                log.warn("已完成 run 的迟到 RunCompleted: runId={}", runId);
                return;
            }

            writeEvent(state, p, null, occurredAt);

            var summary = state.accumulator.snapshot();
            atomicWriter.write(state.runDir.resolve("summary.json"), summary);

            state.completed = true;
            state.eventWriter.close();
        } finally {
            state.lock.unlock();
        }

        activeRuns.remove(runId);
    }

    // ── Regular events ─────────────────────────────────────────────

    private void handleRegularEvent(RunEventPayload payload, String runId,
                                     String parentRunId, Integer turnNumber,
                                     Instant occurredAt) throws IOException {
        RunState state = activeRuns.get(runId);
        if (state == null) {
            log.warn("未找到活跃 run 的事件: runId={}, type={}",
                runId, payload.getClass().getSimpleName());
            return;
        }

        state.lock.lock();
        try {
            if (state.completed) {
                log.warn("已完成 run 的迟到事件: runId={}, type={}",
                    runId, payload.getClass().getSimpleName());
                return;
            }
            writeEvent(state, payload, turnNumber, occurredAt);
        } finally {
            state.lock.unlock();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private void writeEvent(RunState state, RunEventPayload payload,
                             Integer turnNumber, Instant occurredAt) throws IOException {
        long seq = state.nextSequence++;
        RunEventEnvelope envelope = codec.encode(
            payload, state.runId, state.parentRunId, turnNumber, seq, occurredAt);

        String line = MAPPER.writeValueAsString(envelope);
        state.eventWriter.write(line);
        state.eventWriter.newLine();
        state.eventWriter.flush();

        state.accumulator.accept(envelope);
    }

    private void validateRunId(String runId) {
        if (runId == null || !runId.matches(RUN_ID_REGEX)) {
            throw new IllegalArgumentException("Invalid runId: " + runId);
        }
    }

    // ── AutoCloseable ──────────────────────────────────────────────

    @Override
    public void close() {
        for (var entry : activeRuns.entrySet()) {
            RunState state = entry.getValue();
            state.lock.lock();
            try {
                if (state.completed) continue;
                state.accumulator.addWarning("RECORDER_CLOSED_WITHOUT_RUN_COMPLETED");
                var summary = state.accumulator.snapshot();
                try {
                    atomicWriter.write(state.runDir.resolve("summary.json"), summary);
                } catch (IOException e) {
                    log.error("关闭时写入 INCOMPLETE summary 失败: {}", state.runId, e);
                }
                try {
                    state.eventWriter.close();
                } catch (IOException e) {
                    log.error("关闭 writer 失败: {}", state.runId, e);
                }
                state.completed = true;
            } finally {
                state.lock.unlock();
            }
        }
        activeRuns.clear();
    }
}
