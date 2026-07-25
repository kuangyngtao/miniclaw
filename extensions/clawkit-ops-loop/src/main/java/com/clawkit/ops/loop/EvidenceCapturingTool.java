package com.clawkit.ops.loop;

import com.clawkit.ops.mcp.OpsToolResult;
import com.clawkit.tools.Result;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolOutputStats;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Captures the sanitized MCP result at the same boundary used by ToolCallExecutor. */
public final class EvidenceCapturingTool implements Tool {
    private static final Duration MIN_REMAINING_VALIDITY = Duration.ofSeconds(10);
    private static final Map<String, EvidenceType> TYPES = Map.of(
        "service_status", EvidenceType.SERVICE_STATUS,
        "container_status", EvidenceType.CONTAINER_STATUS,
        "ports", EvidenceType.PORT_BINDING,
        "http_probe", EvidenceType.HTTP_PROBE,
        "logs", EvidenceType.LOGS,
        "container_resources", EvidenceType.CONTAINER_RESOURCE,
        "business_metrics", EvidenceType.BUSINESS_METRIC,
        "db_activity", EvidenceType.DB_ACTIVITY,
        "db_lock_graph", EvidenceType.DB_LOCK_GRAPH,
        "db_connection_stats", EvidenceType.DB_CONNECTION_STATS);

    private final Tool delegate;
    private final IncidentEvidenceStore store;
    private final String incidentId;
    private final Duration validity;
    private final Instant freshnessCutoff;
    private final AtomicLong sequence;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public EvidenceCapturingTool(
        Tool delegate, IncidentEvidenceStore store, String incidentId,
        Duration validity, AtomicLong sequence
    ) {
        this(delegate, store, incidentId, validity, Instant.MIN, sequence);
    }

    public EvidenceCapturingTool(
        Tool delegate, IncidentEvidenceStore store, String incidentId,
        Duration validity, Instant freshnessCutoff, AtomicLong sequence
    ) {
        this.delegate = delegate;
        this.store = store;
        this.incidentId = incidentId;
        this.validity = validity;
        this.freshnessCutoff = freshnessCutoff;
        this.sequence = sequence;
        if (!TYPES.containsKey(shortName(delegate.name()))) {
            throw new IllegalArgumentException("unsupported evidence tool: " + delegate.name());
        }
    }

    @Override public String name() { return delegate.name(); }
    @Override public String description() { return delegate.description(); }
    @Override public String inputSchema() { return delegate.inputSchema(); }
    @Override public boolean isReadOnly() { return true; }
    @Override public ToolMetadata metadata() { return delegate.metadata(); }
    @Override @Deprecated public Result<String> execute(String arguments) { return delegate.execute(arguments); }

    @Override public ToolExecutionResult execute(ToolExecutionRequest request) {
        ToolExecutionResult result = delegate.execute(request);
        Evidence evidence = capture(request, result);
        try {
            var output = mapper.readTree(result.output());
            if (output.isObject()) {
                var object = (com.fasterxml.jackson.databind.node.ObjectNode) output;
                object.put("evidenceRef", evidence.evidenceId());
                object.put("evidenceValidUntil", evidence.validUntil().toString());
                object.put("evidenceFreshness", evidence.freshness().name());
                object.put("evidenceCollectionStatus", evidence.collectionStatus().name());
            }
            String augmented = mapper.writeValueAsString(output);
            return new ToolExecutionResult(result.toolCallId(), result.toolName(), augmented,
                result.status(), result.toolError(), result.durationMs(),
                ToolOutputStats.fromOutput(augmented, result.truncated()), result.exitCode(),
                result.metadata(), result.approval(), result.auditId());
        } catch (Exception ignored) {
            return result;
        }
    }

    private Evidence capture(ToolExecutionRequest request, ToolExecutionResult execution) {
        String tool = shortName(delegate.name());
        Instant collectedAt = Instant.now();
        String evidenceId = "e-" + sequence.incrementAndGet();
        try {
            OpsToolResult result = mapper.readValue(execution.output(), OpsToolResult.class);
            var fact = mapper.createObjectNode();
            fact.put("success", result.success());
            fact.set("data", result.data());
            if (result.errorCode() != null) fact.put("errorCode", result.errorCode());
            Instant evidenceObservedAt = evidenceObservedAt(tool, result);
            Instant validUntil = evidenceObservedAt.plus(validity);
            Evidence.Freshness freshness = !result.current()
                ? Evidence.Freshness.HISTORICAL
                : evidenceObservedAt.isBefore(freshnessCutoff)
                    || result.collectedAt().plus(MIN_REMAINING_VALIDITY).isAfter(validUntil)
                    ? Evidence.Freshness.STALE : Evidence.Freshness.CURRENT;
            Evidence evidence = new Evidence(evidenceId, incidentId,
                TYPES.get(tool), "mcp:ops/" + tool, evidenceObservedAt, result.collectedAt(),
                result.target(), Evidence.Kind.FACT, fact,
                "run://" + runId(request) + "/tool/" + request.toolCallId(),
                freshness,
                Evidence.Redaction.SENSITIVE_FIELDS_REMOVED, "2",
                result.success() ? Evidence.CollectionStatus.OBSERVED
                    : Evidence.CollectionStatus.COLLECTION_FAILED,
                validUntil, null);
            store.append(evidence);
            return evidence;
        } catch (Exception error) {
            var fact = mapper.createObjectNode().put("success", false)
                .put("errorCode", "EVIDENCE_CAPTURE_FAILED");
            try {
                Evidence evidence = new Evidence(evidenceId, incidentId,
                    TYPES.get(tool), "mcp:ops/" + tool, collectedAt, collectedAt,
                    tool, Evidence.Kind.FACT, fact,
                    "run://" + runId(request) + "/tool/" + request.toolCallId(),
                    Evidence.Freshness.CURRENT, Evidence.Redaction.SENSITIVE_FIELDS_REMOVED,
                    "2", Evidence.CollectionStatus.COLLECTION_FAILED,
                    collectedAt.plus(validity), null);
                store.append(evidence);
                return evidence;
            } catch (Exception storeError) {
                throw new IllegalStateException("unable to persist collection failure", storeError);
            }
        }
    }

    private static String runId(ToolExecutionRequest request) {
        return request.scope() == null ? "unknown" : request.scope().runId();
    }

    private static Instant evidenceObservedAt(String tool, OpsToolResult result) {
        if (!"logs".equals(tool)) return result.observedAt();
        Instant newest = null;
        for (String line : result.data().path("text").asText("").lines().toList()) {
            int separator = line.indexOf(' ');
            String timestamp = separator < 0 ? line : line.substring(0, separator);
            try {
                Instant candidate = Instant.parse(timestamp);
                if (newest == null || candidate.isAfter(newest)) newest = candidate;
            } catch (Exception ignored) { }
        }
        return newest == null ? result.observedAt() : newest;
    }

    private static String shortName(String name) {
        int marker = name.lastIndexOf("__");
        return marker < 0 ? name : name.substring(marker + 2);
    }
}
