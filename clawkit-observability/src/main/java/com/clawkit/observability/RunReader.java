package com.clawkit.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 从磁盘读取 run 记录，供 CLI /runs、/metrics、/trace 命令使用。
 *
 * <p>流式逐行读取 events.jsonl，单行损坏不影响其他行。
 */
public class RunReader {

    private static final Logger log = LoggerFactory.getLogger(RunReader.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path runsDir;
    private final RunEventCodec codec;

    public RunReader(Path baseDir) {
        this.runsDir = baseDir.resolve("runs");
        this.codec = new RunEventCodec(MAPPER);
    }

    public RunReader() {
        this(Path.of(System.getProperty("user.home"), ".clawkit"));
    }

    // ── List ───────────────────────────────────────────────────────

    /**
     * 列出最近 N 个 run 的 summary，按 startTime 降序排列。
     */
    public List<RunSummary> listRuns(int limit) throws IOException {
        if (!Files.isDirectory(runsDir)) return List.of();

        List<RunSummary> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(runsDir)) {
            for (var dir : dirs.filter(Files::isDirectory).toList()) {
                readSummary(dir).ifPresent(result::add);
            }
        }
        // Sort by startTime DESC (independent of directory name)
        result.sort(Comparator.comparing(
            RunSummary::startTime,
            Comparator.nullsLast(Comparator.reverseOrder())));
        // Limit AFTER sorting by startTime
        if (result.size() > limit) {
            return result.subList(0, limit);
        }
        return result;
    }

    // ── Summary ────────────────────────────────────────────────────

    /** 读取指定 run 的 summary.json（新格式） */
    public Optional<RunSummary> readSummary(String runId) throws IOException {
        Path runDir = validateAndResolve(runId);
        return readSummary(runDir);
    }

    private Optional<RunSummary> readSummary(Path runDir) {
        Path summaryFile = runDir.resolve("summary.json");
        if (!Files.exists(summaryFile)) return Optional.empty();

        try {
            return Optional.of(MAPPER.readValue(summaryFile.toFile(), RunSummary.class));
        } catch (IOException e) {
            // 尝试兼容旧格式
            try {
                var old = MAPPER.readValue(summaryFile.toFile(),
                    com.clawkit.observability.model.RunMetrics.class);
                return Optional.of(convertLegacy(old));
            } catch (IOException e2) {
                log.warn("无法读取 summary: {}", summaryFile, e2);
                return Optional.empty();
            }
        }
    }

    // ── Events ─────────────────────────────────────────────────────

    /**
     * 流式逐行读取 events.jsonl。
     * 单行损坏跳过并记录 warning，不中断读取。
     */
    public ReadResult<List<RunEventEnvelope>> readEvents(String runId) throws IOException {
        Path runDir = validateAndResolve(runId);
        Path eventsFile = runDir.resolve("events.jsonl");

        if (!Files.exists(eventsFile)) {
            return ReadResult.of(List.of(), List.of(
                ReadWarning.of(eventsFile.toString(), 0,
                    "MISSING_FILE", "events.jsonl 不存在")));
        }

        List<RunEventEnvelope> events = new ArrayList<>();
        List<ReadWarning> warnings = new ArrayList<>();
        long lineNumber = 0;

        try (BufferedReader reader = Files.newBufferedReader(eventsFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    RunEventEnvelope env = codec.deserialize(line);
                    events.add(env);

                    if (env.payload() instanceof UnknownEventPayload) {
                        warnings.add(ReadWarning.of(
                            eventsFile.toString(), lineNumber,
                            ReadWarning.UNKNOWN_EVENT_TYPE,
                            "未知事件类型: " + env.eventType()));
                    }
                    if (env.schemaVersion() > RunEventEnvelope.CURRENT_SCHEMA_VERSION) {
                        warnings.add(ReadWarning.of(
                            eventsFile.toString(), lineNumber,
                            ReadWarning.UNSUPPORTED_SCHEMA,
                            "未来 schema 版本: " + env.schemaVersion()));
                    }
                } catch (Exception e) {
                    warnings.add(ReadWarning.of(
                        eventsFile.toString(), lineNumber,
                        ReadWarning.INVALID_JSON,
                        "JSON 解析失败: " + e.getMessage()));
                }
            }
        }

        return ReadResult.of(events, warnings);
    }

    // ── Metrics ────────────────────────────────────────────────────

    /**
     * 读取 events 后动态投影指标。
     * 不再从 summary.json 读取指标。
     */
    public ReadResult<RunMetrics> readMetrics(String runId) throws IOException {
        ReadResult<List<RunEventEnvelope>> eventsResult = readEvents(runId);
        if (eventsResult.value() == null || eventsResult.value().isEmpty()) {
            return ReadResult.empty(eventsResult.warnings());
        }

        var projection = RunMetricsProjector.project(eventsResult.value());
        return ReadResult.of(projection, eventsResult.warnings());
    }

    // ── Legacy (deprecated, kept for temporary compat) ─────────────

    /**
     * @deprecated 使用 {@link #readEvents(String)} 替代
     */
    @Deprecated
    public List<String> readTrace(String runId) throws IOException {
        Path runDir = validateAndResolve(runId);
        Path eventsFile = runDir.resolve("events.jsonl");
        if (!Files.exists(eventsFile)) {
            // fallback to legacy trace.jsonl
            eventsFile = runDir.resolve("trace.jsonl");
        }
        if (!Files.exists(eventsFile)) return List.of();
        return Files.readAllLines(eventsFile);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private Path validateAndResolve(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        if (runId.contains("..") || runId.contains("/") || runId.contains("\\")) {
            throw new IllegalArgumentException("Invalid runId (路径逃逸): " + runId);
        }
        Path runDir = runsDir.resolve(runId).normalize();
        if (!runDir.startsWith(runsDir)) {
            throw new IllegalArgumentException("Invalid runId (路径逃逸): " + runId);
        }
        return runDir;
    }

    private Path runDir(String runId) {
        return runsDir.resolve(runId);
    }

    /** 兼容旧版 summary.json（model/RunMetrics 格式） */
    private static RunSummary convertLegacy(com.clawkit.observability.model.RunMetrics old) {
        return new RunSummary(
            RunSummary.CURRENT_SCHEMA_VERSION,
            old.runId(),
            null, // parentRunId — legacy doesn't have it
            old.taskSummary(),
            old.status(),
            old.status() != RunStatus.RUNNING && old.status() != RunStatus.INCOMPLETE,
            old.startTime(),
            old.endTime(),
            old.durationMs(),
            old.turns(),
            0, // providerCalls — legacy doesn't track
            0, 0, 0, 0, 0, false,
            old.toolCalls(),
            old.toolFailures(),
            0, 0, 0,
            0, 0, 0, 0,
            0, 0,
            old.compactCount(), 0,
            old.permissionMode(), old.thinkingMode(), old.executionMode(),
            old.workDir(), old.model(),
            old.errorCode(), old.errorMessage(),
            List.of()
        );
    }
}
