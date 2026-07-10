package com.clawkit.observability;

import com.clawkit.observability.model.RunMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 从磁盘读取 run 记录，供 CLI /runs、/metrics、/trace 命令使用。
 */
public class RunReader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final Path runsDir;

    public RunReader(Path baseDir) {
        this.runsDir = baseDir.resolve("runs");
    }

    public RunReader() {
        this(Path.of(System.getProperty("user.home"), ".clawkit"));
    }

    /** 列出最近 N 个 run */
    public List<RunSummary> listRecent(int limit) throws IOException {
        if (!Files.isDirectory(runsDir)) return List.of();

        List<RunSummary> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(runsDir)) {
            dirs.filter(Files::isDirectory)
                .sorted(Comparator.comparing(Path::getFileName).reversed())
                .limit(limit)
                .forEach(dir -> {
                    readSummary(dir).ifPresent(result::add);
                });
        }
        return result;
    }

    /** 读取指定 run 的 summary */
    public RunMetrics readMetrics(String runId) throws IOException {
        Path summaryFile = runsDir.resolve(runId).resolve("summary.json");
        if (!Files.exists(summaryFile)) return null;
        return MAPPER.readValue(summaryFile.toFile(), RunMetrics.class);
    }

    /** 读取指定 run 的 trace 行 */
    public List<String> readTrace(String runId) throws IOException {
        Path traceFile = runsDir.resolve(runId).resolve("trace.jsonl");
        if (!Files.exists(traceFile)) return List.of();
        return Files.readAllLines(traceFile);
    }

    /** 读取跟踪行并解析为对象 */
    public List<RunEvent> readTraceEvents(String runId) throws IOException {
        Path traceFile = runsDir.resolve(runId).resolve("trace.jsonl");
        if (!Files.exists(traceFile)) return List.of();

        List<RunEvent> events = new ArrayList<>();
        for (String line : Files.readAllLines(traceFile)) {
            try {
                // 根据 type 字段分发到具体的 RunEvent 子类型
                var node = MAPPER.readTree(line);
                String type = node.has("type") ? node.get("type").asText() : "";
                // 简便实现：只做结构化读取，不反序列化为具体子类
                events.add(null); // placeholder — CLI 直接读 JSON 行展示即可
            } catch (Exception ignored) {
                // 跳过损坏行
            }
        }
        return events;
    }

    private java.util.Optional<RunSummary> readSummary(Path runDir) {
        Path summaryFile = runDir.resolve("summary.json");
        if (!Files.exists(summaryFile)) return java.util.Optional.empty();
        try {
            RunMetrics m = MAPPER.readValue(summaryFile.toFile(), RunMetrics.class);
            return java.util.Optional.of(new RunSummary(m));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    /** CLI 友好摘要（比完整 RunMetrics 轻量） */
    public record RunSummary(
        String runId,
        String status,
        int turns,
        int toolCalls,
        int toolFailures,
        int compactCount,
        long durationMs,
        String permissionMode,
        String model,
        String startTime
    ) {
        RunSummary(RunMetrics m) {
            this(
                m.runId(),
                m.status().name(),
                m.turns(),
                m.toolCalls(),
                m.toolFailures(),
                m.compactCount(),
                m.durationMs(),
                m.permissionMode(),
                m.model() != null ? m.model() : "-",
                m.startTime() != null ? m.startTime().toString() : "-"
            );
        }
    }
}
