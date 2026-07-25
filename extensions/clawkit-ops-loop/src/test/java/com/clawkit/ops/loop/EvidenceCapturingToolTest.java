package com.clawkit.ops.loop;

import com.clawkit.ops.mcp.OpsToolResult;
import com.clawkit.tools.Result;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolExecutionScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceCapturingToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @TempDir Path temp;

    @Test
    void capturesAtStructuredExecutionBoundaryAndReturnsReferenceToModel() throws Exception {
        OpsToolResult payload = new OpsToolResult("db_lock_graph", "postgres/current_database",
            Instant.parse("2026-07-21T00:00:00Z"), Instant.parse("2026-07-21T00:00:01Z"),
            true, true, MAPPER.createObjectNode().put("rowCount", 1), null, null,
            new OpsToolResult.Audit("stub", 1, 1000, 10, 10, false));
        Tool delegate = new StubTool(MAPPER.writeValueAsString(payload));
        IncidentEvidenceStore store = new IncidentEvidenceStore(temp.resolve("evidence.jsonl"));
        EvidenceCapturingTool tool = new EvidenceCapturingTool(delegate, store,
            "inc-1", Duration.ofSeconds(30), new AtomicLong());
        ToolExecutionRequest request = new ToolExecutionRequest("call-1", delegate.name(),
            MAPPER.createObjectNode(), new ToolExecutionScope("run-1", 2, temp, java.util.Set.of()));

        var result = tool.execute(request);

        assertThat(result.output()).contains("\"evidenceRef\":\"e-1\"");
        assertThat(result.output()).contains("\"evidenceValidUntil\":\"2026-07-21T00:00:30Z\"");
        assertThat(result.output()).contains("\"evidenceFreshness\":\"CURRENT\"");
        assertThat(result.output()).contains("\"evidenceCollectionStatus\":\"OBSERVED\"");
        assertThat(store.snapshot()).singleElement().satisfies(evidence -> {
            assertThat(evidence.type()).isEqualTo(EvidenceType.DB_LOCK_GRAPH);
            assertThat(evidence.rawReference()).isEqualTo("run://run-1/tool/call-1");
            assertThat(evidence.validUntil()).isEqualTo(Instant.parse("2026-07-21T00:00:30Z"));
        });
    }

    @Test
    void derivesLogFreshnessFromNewestDockerTimestampNotCollectionTime() throws Exception {
        var data = MAPPER.createObjectNode().put("text",
            "2026-07-21T00:00:00Z historical diagnostic marker\n");
        OpsToolResult payload = new OpsToolResult("logs", "order-api",
            Instant.parse("2026-07-21T00:01:00Z"), Instant.parse("2026-07-21T00:01:01Z"),
            true, true, data, null, null,
            new OpsToolResult.Audit("stub", 1, 1000, 10, 10, false));
        Tool delegate = new NamedStubTool("mcp__ops__logs", MAPPER.writeValueAsString(payload));
        IncidentEvidenceStore store = new IncidentEvidenceStore(temp.resolve("logs.jsonl"));
        EvidenceCapturingTool tool = new EvidenceCapturingTool(delegate, store,
            "inc-1", Duration.ofSeconds(30), new AtomicLong());

        tool.execute(new ToolExecutionRequest("call-2", delegate.name(),
            MAPPER.createObjectNode(), new ToolExecutionScope("run-1", 2, temp, java.util.Set.of())));

        assertThat(store.snapshot().getFirst().freshness()).isEqualTo(Evidence.Freshness.STALE);
        assertThat(store.snapshot().getFirst().observedAt())
            .isEqualTo(Instant.parse("2026-07-21T00:00:00Z"));
    }

    @Test
    void marksEvidenceStaleWhenValidityCannotCoverDiagnosisHorizon() throws Exception {
        OpsToolResult payload = new OpsToolResult("db_lock_graph", "postgres/current_database",
            Instant.parse("2026-07-21T00:00:00Z"), Instant.parse("2026-07-21T00:00:25Z"),
            true, true, MAPPER.createObjectNode().put("rowCount", 1), null, null,
            new OpsToolResult.Audit("stub", 1, 1000, 10, 10, false));
        Tool delegate = new StubTool(MAPPER.writeValueAsString(payload));
        IncidentEvidenceStore store = new IncidentEvidenceStore(temp.resolve("near-expiry.jsonl"));
        EvidenceCapturingTool tool = new EvidenceCapturingTool(delegate, store,
            "inc-1", Duration.ofSeconds(30), new AtomicLong());

        var result = tool.execute(new ToolExecutionRequest("call-3", delegate.name(),
            MAPPER.createObjectNode(), new ToolExecutionScope("run-1", 2, temp, java.util.Set.of())));

        assertThat(result.output()).contains("\"evidenceFreshness\":\"STALE\"");
        assertThat(store.snapshot().getFirst().freshness()).isEqualTo(Evidence.Freshness.STALE);
    }

    @Test
    void marksLogOlderThanIncidentFreshnessHorizonAsStale() throws Exception {
        var data = MAPPER.createObjectNode().put("text",
            "2026-07-21T00:00:00Z historical marker\n");
        OpsToolResult payload = new OpsToolResult("logs", "order-api",
            Instant.parse("2026-07-21T00:01:00Z"), Instant.parse("2026-07-21T00:01:01Z"),
            true, true, data, null, null,
            new OpsToolResult.Audit("stub", 1, 1000, 10, 10, false));
        Tool delegate = new NamedStubTool("mcp__ops__logs", MAPPER.writeValueAsString(payload));
        IncidentEvidenceStore store = new IncidentEvidenceStore(temp.resolve("cutoff.jsonl"));
        EvidenceCapturingTool tool = new EvidenceCapturingTool(delegate, store, "inc-1",
            Duration.ofSeconds(120), Instant.parse("2026-07-21T00:00:30Z"), new AtomicLong());

        tool.execute(new ToolExecutionRequest("call-4", delegate.name(), MAPPER.createObjectNode(),
            new ToolExecutionScope("run-1", 2, temp, java.util.Set.of())));

        assertThat(store.snapshot().getFirst().freshness()).isEqualTo(Evidence.Freshness.STALE);
    }

    private record StubTool(String output) implements Tool {
        @Override public String name() { return "mcp__ops__db_lock_graph"; }
        @Override public String description() { return "stub"; }
        @Override public String inputSchema() { return "{\"type\":\"object\"}"; }
        @Override public boolean isReadOnly() { return true; }
        @Override @Deprecated public Result<String> execute(String arguments) { return new Result.Ok<>(output); }
    }

    private record NamedStubTool(String name, String output) implements Tool {
        @Override public String description() { return "stub"; }
        @Override public String inputSchema() { return "{\"type\":\"object\"}"; }
        @Override public boolean isReadOnly() { return true; }
        @Override @Deprecated public Result<String> execute(String arguments) { return new Result.Ok<>(output); }
    }
}
