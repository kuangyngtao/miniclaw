package com.clawkit.observability;

import java.io.BufferedWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单个 run 的 recorder 内部状态。
 * 每个 run 独立持有 writer、lock、sequence、accumulator。
 */
final class RunState {

    final String runId;
    final Path runDir;
    final BufferedWriter eventWriter;
    final ReentrantLock lock = new ReentrantLock();
    final RunAccumulator accumulator;

    String parentRunId;
    long nextSequence = 1;
    boolean completed = false;

    /** 仅用于 close() 时生成 INCOMPLETE summary */
    Instant startTime;

    RunState(String runId, Path runDir, BufferedWriter eventWriter) {
        this.runId = runId;
        this.runDir = runDir;
        this.eventWriter = eventWriter;
        this.accumulator = new RunAccumulator(runId);
    }
}
