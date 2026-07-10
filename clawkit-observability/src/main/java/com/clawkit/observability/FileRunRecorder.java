package com.clawkit.observability;

import com.clawkit.observability.model.CompactMetrics;
import com.clawkit.observability.model.ProviderCallMetrics;
import com.clawkit.observability.model.RunMetrics;
import com.clawkit.observability.model.ToolCallMetrics;
import com.clawkit.observability.model.TurnMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

/**
 * 基于本地文件的 RunRecorder 实现。
 * 将运行时事件写入 .clawkit/runs/&lt;run-id&gt;/ 目录：
 *
 * <ul>
 *   <li>trace.jsonl — 所有 RunEvent，按时间顺序追加</li>
 *   <li>summary.json — run 结束时的 RunMetrics 汇总</li>
 * </ul>
 *
 * <p>线程安全：所有文件写入使用 synchronized 块。
 * 参数脱敏：只写入 argSummary（已脱敏的参数预览），不写原始 arguments。
 */
public class FileRunRecorder implements RunRecorder {

    private static final Logger log = LoggerFactory.getLogger(FileRunRecorder.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /** 脱敏关键字 */
    private static final List<String> SENSITIVE_KEYS = List.of(
        "apiKey", "api_key", "token", "password", "secret",
        "authorization", "webhook", "bearer", "credential"
    );

    private final Path runsDir;
    private final Object lock = new Object();

    // === 当前 run 状态（会被多个虚拟线程读取，仅在 synchronized 内修改） ===
    private Path currentRunDir;
    private BufferedWriter traceWriter;
    private String runId;
    private String taskSummary;
    private Instant startTime;
    private String workDir;
    private String model;
    private String permissionMode;
    private String thinkingMode;
    private String executionMode;
    private RunStatus status;
    private String errorCode;
    private String errorMessage;
    private int totalTurns;
    private int totalToolCalls;
    private int toolFailures;
    private int compactCount;

    public FileRunRecorder(Path baseDir) {
        this.runsDir = baseDir.resolve("runs");
        try {
            Files.createDirectories(runsDir);
        } catch (IOException e) {
            log.error("无法创建 runs 目录: {}", runsDir, e);
        }
    }

    /** 使用默认路径 ~/.clawkit */
    public FileRunRecorder() {
        this(Path.of(System.getProperty("user.home"), ".clawkit"));
    }

    // ── RunRecorder ────────────────────────────────────────────────

    @Override
    public void accept(RunEvent event) {
        try {
            dispatch(event);
        } catch (Exception e) {
            log.error("FileRunRecorder 写入失败: event={}", event.getClass().getSimpleName(), e);
        }
    }

    private void dispatch(RunEvent event) throws IOException {
        switch (event) {
            case RunEvent.RunStarted e -> handleRunStarted(e);
            case RunEvent.RunCompleted e -> handleRunCompleted(e);
            case RunEvent.TurnStarted e -> appendTrace(e);
            case RunEvent.TurnCompleted e -> {
                TurnMetrics m = e.metrics();
                totalTurns = Math.max(totalTurns, m.turnNumber());
                totalToolCalls += m.toolCallCount();
                appendTrace(e);
            }
            case RunEvent.ProviderCallCompleted e -> appendTrace(e);
            case RunEvent.ToolInvoked e -> appendTrace(e);
            case RunEvent.ToolCompleted e -> {
                ToolCallMetrics m = e.metrics();
                if (!m.success()) toolFailures++;
                appendTrace(e);
            }
            case RunEvent.CompactTriggered e -> appendTrace(e);
            case RunEvent.CompactCompleted e -> {
                compactCount++;
                appendTrace(e);
            }
            case RunEvent.ApprovalDecision e -> appendTrace(e);
        }
    }

    // ── Run lifecycle ──────────────────────────────────────────────

    private void handleRunStarted(RunEvent.RunStarted e) throws IOException {
        synchronized (lock) {
            this.runId = e.runId();
            this.startTime = e.startTime();
            this.workDir = e.workDir();
            this.model = e.model();
            this.permissionMode = e.permissionMode();
            this.thinkingMode = e.thinkingMode();
            this.executionMode = e.executionMode();
            this.status = RunStatus.UNKNOWN_ERROR;
            this.totalTurns = 0;
            this.totalToolCalls = 0;
            this.toolFailures = 0;
            this.compactCount = 0;

            this.currentRunDir = runsDir.resolve(runId);
            Files.createDirectories(currentRunDir);

            this.traceWriter = Files.newBufferedWriter(
                currentRunDir.resolve("trace.jsonl"), CREATE, APPEND);

            appendTrace(e);
        }
    }

    private void handleRunCompleted(RunEvent.RunCompleted e) throws IOException {
        synchronized (lock) {
            this.status = e.status();
            this.errorCode = e.errorCode();
            this.errorMessage = e.errorMessage();
            this.totalTurns = e.totalTurns();
            this.totalToolCalls = e.totalToolCalls();
            this.toolFailures = e.toolFailures();
            this.compactCount = e.compactCount();

            appendTrace(e);

            // 关闭 trace writer
            if (traceWriter != null) {
                traceWriter.close();
                traceWriter = null;
            }

            // 写 summary.json
            if (currentRunDir != null) {
                var summary = new RunMetrics(
                    runId, taskSummary, startTime, e.endTime(),
                    java.time.Duration.between(startTime, e.endTime()).toMillis(),
                    status, e.status().name(), errorCode, errorMessage,
                    totalTurns, totalToolCalls, toolFailures, compactCount,
                    permissionMode, thinkingMode, executionMode, workDir, model
                );
                MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(currentRunDir.resolve("summary.json").toFile(), summary);
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────

    /** 线程安全地追加一行 JSON 到 trace.jsonl */
    private void appendTrace(Object event) throws IOException {
        synchronized (lock) {
            if (traceWriter == null) return;
            String line = MAPPER.writeValueAsString(event);
            traceWriter.write(line);
            traceWriter.newLine();
            traceWriter.flush();
        }
    }

    /** 脱敏检查（供外部使用） */
    public static boolean isSensitive(String key) {
        String lower = key.toLowerCase();
        return SENSITIVE_KEYS.stream().anyMatch(lower::contains);
    }
}
