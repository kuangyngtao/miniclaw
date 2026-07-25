package com.clawkit.ops.loop;

import com.clawkit.engine.AgentRuntimeDependencies;
import com.clawkit.engine.PermissionMode;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.engine.impl.InternalToolRouter;
import com.clawkit.engine.impl.ObservingProviderGateway;
import com.clawkit.engine.impl.ToolCallExecutor;
import com.clawkit.engine.impl.ToolExecutionContext;
import com.clawkit.observability.CompositeRunRecorder;
import com.clawkit.observability.FileRunRecorder;
import com.clawkit.ops.mcp.OpsCapabilityProfile;
import com.clawkit.provider.LLMConfig;
import com.clawkit.provider.ProviderFactory;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.ApprovalGrantCache;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.mcp.McpConfig;
import com.clawkit.tools.mcp.McpManager;
import com.clawkit.tools.mcp.McpServerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import com.clawkit.tools.schema.ToolCall;

/** Real-provider child process. It has no filesystem, shell, network, or write tools. */
public final class OpsBlindAgentMain {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private OpsBlindAgentMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("usage: <incident-input.json> <output-dir> <mcp-url>");
        Path inputPath = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(output);
        IncidentInput input = MAPPER.readValue(inputPath.toFile(), IncidentInput.class);
        if (!OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1.name().equals(input.capabilityProfile())) {
            throw new IllegalArgumentException("unsupported capability profile");
        }

        LLMConfig config = modelConfig(System.getenv());
        var provider = ProviderFactory.create(config);
        var fileRecorder = new FileRunRecorder(output);
        var recorder = new CompositeRunRecorder(fileRecorder);
        var baselineRegistry = new ToolRegistry();
        var manager = new McpManager();
        try {
            McpServerConfig server = new McpServerConfig("ops", null, List.of(),
                args[2], Map.of(), false, true);
            var tools = manager.startAll(new McpConfig(Map.of("ops", server)), output);
            if (tools.size() != OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1.toolNames().size()
                || tools.stream().anyMatch(tool -> !tool.isReadOnly())) {
                throw new IllegalStateException("OPS MCP capability boundary validation failed: "
                    + tools.stream().map(tool -> tool.name() + "[readOnly=" + tool.isReadOnly() + "]").toList());
            }
            IncidentFlightRecorder flight = new IncidentFlightRecorder(
                output.resolve("incident-events.jsonl"), Clock.systemUTC());
            flight.record("PERMISSION_BOUNDARY", null, Map.of(
                "capabilityProfile", input.capabilityProfile(),
                "toolCount", tools.size(), "readOnlyOnly", true));
            IncidentEvidenceStore evidenceStore = new IncidentEvidenceStore(output.resolve("evidence.jsonl"));
            AtomicLong sequence = new AtomicLong();
            tools.forEach(tool -> baselineRegistry.register(new EvidenceCapturingTool(
                tool, evidenceStore, input.incidentId(),
                Duration.ofSeconds(120), input.detectedAt().minusSeconds(30), sequence)));

            collectBaseline(baselineRegistry, tools, recorder, input.incidentId());
            List<Evidence> baselineEvidence = evidenceStore.snapshot();
            DiagnosticSignals signals = DiagnosticSignals.extract(baselineEvidence);
            Files.writeString(output.resolve("diagnostic-signals.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(signals));
            DiagnosisSubmissionTool submission = new DiagnosisSubmissionTool();
            var agentRegistry = diagnosisRegistry(submission);

            var gateway = new ObservingProviderGateway(provider, recorder);
            var dependencies = new AgentRuntimeDependencies(gateway, null, agentRegistry,
                config.contextWindow(), config.encoding(), recorder,
                AgentRuntimeDependencies.noopMemoryHooks(),
                AgentRuntimeDependencies.emptySkillRuntime());
            AgentEngine engine = new AgentEngine(dependencies, output.toString(), ThinkingMode.OFF, "");
            engine.setCompactionHintProvider(
                new OpsCompactionHintProvider(input, signals, evidenceStore::snapshot));
            engine.setPermissionMode(PermissionMode.PLAN);
            engine.setWorkspaceRules("You are a read-only production incident diagnostician. "
                + "Baseline collection is complete. Use only submit_diagnosis and never request more tools. "
                + "Never claim a repair. "
                + "Treat evidence timestamps and contradictory evidence explicitly.");
            String response = engine.run(prompt(input, signals, baselineEvidence));
            Files.writeString(output.resolve("model-response.txt"), response);
            List<Evidence> evidence = evidenceStore.snapshot();
            Instant evaluatedAt = Instant.now();
            Diagnosis modelDiagnosis = submission.latest() != null
                ? submission.latest() : parseDiagnosis(response);
            Diagnosis diagnosis = sanitizeSupportingEvidence(
                DiagnosisReconciler.reconcile(modelDiagnosis, signals, evidence, evaluatedAt),
                evidence, evaluatedAt);
            for (Evidence item : evidence) {
                flight.record("EVIDENCE_CAPTURED", item.rawReference(), Map.of(
                    "evidenceId", item.evidenceId(), "type", item.type().name(),
                    "freshness", item.freshness().name(),
                    "collectionStatus", item.collectionStatus().name()));
            }
            flight.record("HYPOTHESIS", null, Map.of(
                "selected", diagnosis.rootCauseCode(), "alternatives", diagnosis.alternatives()));
            flight.record("EXCLUSION", null, Map.of(
                "contradictingEvidence", diagnosis.contradictingEvidence(),
                "missingEvidence", diagnosis.missingEvidence()));
            flight.record("DIAGNOSIS", null, Map.of(
                "status", diagnosis.diagnosisStatus().name(),
                "currentCondition", diagnosis.currentCondition().name(),
                "supportingEvidence", diagnosis.supportingEvidence()));
            String runId = evidence.stream().map(Evidence::rawReference)
                .map(OpsBlindAgentMain::runIdFromReference).filter(id -> id != null)
                .filter(id -> !id.startsWith("baseline-")).findFirst()
                .orElse("baseline-" + input.incidentId());
            EvidenceBundle bundle = new EvidenceBundle(input.incidentId(), runId, Instant.now(), evidence);
            IncidentReport report = new IncidentReport(input.incidentId(), runId,
                IncidentState.READ_ONLY_COMPLETE, input.detectedAt(), Instant.now(), bundle,
                diagnosis, List.of(), "2", input.capabilityProfile(), config.model(),
                input.promptVersion(), "incident-events.jsonl");
            new IncidentReportWriter().write(output, report);
        } finally {
            manager.shutdown();
            fileRecorder.close();
        }
    }

    private static String prompt(
        IncidentInput input, DiagnosticSignals signals, List<Evidence> baselineEvidence
    ) throws Exception {
        return "Diagnose this incident using the already collected bounded baseline evidence below. "
            + "Do not request additional evidence or call diagnostic tools; submit the result directly. "
            + "CONNECTION_EXHAUSTION means the order-api application pool is saturated "
            + "(active >= max and pending > 0); PostgreSQL global max_connections need not be exhausted. "
            + "CPU_PRESSURE means order-api CPU reached at least 100 percent during the incident window; "
            + "a later low point sample can establish recovery but does not erase the historical root cause. "
            + "Deterministic extracted signals are authoritative under the taxonomy because they are derived "
            + "only from bounded evidence, not ground truth. Use candidateRootCause unless stronger direct "
            + "evidence establishes another listed cause; a later lower point sample does not erase an "
            + "incident-window maximum or an earlier observed pending queue. Signals: "
            + MAPPER.writeValueAsString(signals) + ". "
            + "Baseline evidence: " + MAPPER.writeValueAsString(baselineEvidence) + ". "
            + "Submit the final result by calling submit_diagnosis exactly once. After it is accepted, "
            + "respond with a short confirmation only. supportingEvidence and "
            + "contradictingEvidence must be JSON arrays containing only evidence ID strings shown by tool "
            + "evidence references, for example [\"e-1\",\"e-2\"], never objects. "
            + "Only cite evidence in supportingEvidence when evidenceCollectionStatus is OBSERVED, "
            + "evidenceFreshness is CURRENT, and evidenceValidUntil will still be in the future when you "
            + "finish the diagnosis. The supplied baseline is the complete evidence set for this bounded run. "
            + "Do not use STALE evidence to establish root cause, current condition, or resolution attribution; "
            + "treat its contents only as unavailable historical context and record the missing fresh evidence. "
            + "Valid rootCauseCode values: DB_LOCK_WAIT, CPU_PRESSURE, CONNECTION_EXHAUSTION, INCONCLUSIVE. "
            + "For CONFIRMED or PROBABLE diagnoses, alternatives must contain at least one other root cause "
            + "code considered; only INCONCLUSIVE may use an empty alternatives array. "
            + "Valid diagnosisStatus values: CONFIRMED, PROBABLE, INCONCLUSIVE. Valid currentCondition "
            + "values: ACTIVE, RECOVERED, UNKNOWN. Valid resolutionAttribution: NONE, SELF_RECOVERED. "
            + "For this workload, a current business p95LatencyMs of 500 or more is degraded even when "
            + "errorCount is zero. An INCONCLUSIVE diagnosis must still cite current supporting evidence "
            + "that establishes the symptom or the inability to distinguish the listed causes; do not return "
            + "an empty supportingEvidence array. "
            + "For a recovered incident, an empty current lock graph does not disprove a historical DB lock wait. "
            + "Correlate CURRENT logs of a completed lock-holding transaction with the incident window and business "
            + "error metrics; only when that current evidence establishes a past lock wait and current business "
            + "metrics are healthy, "
            + "use DB_LOCK_WAIT, currentCondition RECOVERED, and resolutionAttribution SELF_RECOVERED. "
            + "If current business metrics are degraded while current resource, lock, activity, and connection "
            + "evidence do not establish one of the listed causes, use INCONCLUSIVE with currentCondition ACTIVE "
            + "and resolutionAttribution NONE, even when stale logs mention a previously cleared wait. "
            + "Set recommendedActionCode=ESCALATE and claimedResolved=false. If evidence cannot distinguish a cause, use rootCauseCode "
            + "INCONCLUSIVE and diagnosisStatus INCONCLUSIVE. Do not add a JSON Schema or fields outside "
            + "Diagnosis v2. Include exactly these fields: rootCauseCode, confidence, supportingEvidence, "
            + "contradictingEvidence, alternatives, missingEvidence, recommendedActionCode, claimedResolved, "
            + "schemaVersion, diagnosisStatus, currentCondition, evaluatedAt, resolutionAttribution. Incident: "
            + MAPPER.writeValueAsString(input);
    }

    static ToolRegistry diagnosisRegistry(DiagnosisSubmissionTool submission) {
        var registry = new ToolRegistry();
        registry.register(submission);
        return registry;
    }

    private static void collectBaseline(
        ToolRegistry registry, List<com.clawkit.tools.Tool> tools,
        com.clawkit.observability.RunRecorder recorder, String incidentId
    ) {
        Map<String, String> names = tools.stream().collect(java.util.stream.Collectors.toMap(
            tool -> shortName(tool.name()), com.clawkit.tools.Tool::name));
        List<ToolCall> calls = new ArrayList<>();
        calls.add(call("baseline-http", names, "http_probe", Map.of("endpoint", "gateway-live")));
        calls.add(call("baseline-metrics-incident", names, "business_metrics",
            Map.of("endpoint", "business-metrics-incident")));
        calls.add(call("baseline-metrics-current", names, "business_metrics",
            Map.of("endpoint", "business-metrics-current")));
        calls.add(call("baseline-resource-order-api", names, "container_resources",
            Map.of("service", "order-api")));
        calls.add(call("baseline-logs-order-api", names, "logs",
            Map.of("service", "order-api", "windowSeconds", 120, "tail", 100)));
        calls.add(call("baseline-db-activity", names, "db_activity", Map.of()));
        calls.add(call("baseline-db-locks", names, "db_lock_graph", Map.of()));
        calls.add(call("baseline-db-connections", names, "db_connection_stats", Map.of()));

        var permissionPolicy = (com.clawkit.tools.PermissionPolicy) (mode, metadata, request, grants) ->
            metadata.isReadOnly()
                ? com.clawkit.tools.PermissionPolicy.PermissionDecision.allow()
                : com.clawkit.tools.PermissionPolicy.PermissionDecision.deny(
                    "BASELINE_READ_ONLY", "baseline collection permits read-only tools only");
        var context = new ToolExecutionContext("baseline-" + incidentId, 0,
            com.clawkit.tools.PermissionMode.PLAN, permissionPolicy, null, recorder,
            new InternalToolRouter(), ApprovalGrantCache.noop(), ExecutionControl.none());
        new ToolCallExecutor(registry).executeBatch(calls, context);
    }

    private static ToolCall call(
        String id, Map<String, String> names, String shortName, Map<String, ?> arguments
    ) {
        String name = names.get(shortName);
        if (name == null) throw new IllegalStateException("missing baseline tool: " + shortName);
        return new ToolCall(id, name, MAPPER.valueToTree(arguments));
    }

    private static String shortName(String name) {
        int marker = name.lastIndexOf("__");
        return marker < 0 ? name : name.substring(marker + 2);
    }

    private static String extractJson(String response) {
        var matcher = java.util.regex.Pattern.compile(
            "```(?:json)?\\s*(\\{.*?})\\s*```", java.util.regex.Pattern.DOTALL
                | java.util.regex.Pattern.CASE_INSENSITIVE).matcher(response);
        String fenced = null;
        while (matcher.find()) fenced = matcher.group(1);
        if (fenced != null) return fenced;
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("model did not return diagnosis JSON");
        return response.substring(start, end + 1);
    }

    static Diagnosis parseDiagnosis(String response) throws Exception {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(extractJson(response));
        for (String field : List.of("supportingEvidence", "contradictingEvidence",
            "alternatives", "missingEvidence")) {
            if (!root.has(field) || root.get(field).isNull()) root.putArray(field);
            else if (root.get(field).isTextual()) {
                String value = root.get(field).asText();
                root.putArray(field).add(value);
            }
        }
        if (root.path("confidence").isTextual()) {
            String confidence = root.path("confidence").asText().trim();
            try {
                root.put("confidence", normalizeConfidence(Double.parseDouble(confidence)));
            } catch (NumberFormatException ignored) {
                root.put("confidence", switch (confidence.toUpperCase(java.util.Locale.ROOT)) {
                    case "CONFIRMED", "HIGH" -> 0.9;
                    case "PROBABLE", "MEDIUM" -> 0.7;
                    case "INCONCLUSIVE", "LOW" -> 0.3;
                    default -> 0.0;
                });
            }
        } else if (root.path("confidence").isNumber()) {
            root.put("confidence", normalizeConfidence(root.path("confidence").asDouble()));
        }
        normalizeEvidenceIds(root, "supportingEvidence");
        normalizeEvidenceIds(root, "contradictingEvidence");
        normalizeObjectStrings(root, "alternatives", "rootCauseCode");
        return MAPPER.readerFor(Diagnosis.class)
            .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .readValue(root);
    }

    private static double normalizeConfidence(double confidence) {
        return confidence > 1.0 && confidence <= 100.0 ? confidence / 100.0 : confidence;
    }

    static Diagnosis sanitizeSupportingEvidence(
        Diagnosis diagnosis, List<Evidence> evidence, Instant evaluatedAt
    ) {
        Map<String, Evidence> byId = evidence.stream().collect(
            java.util.stream.Collectors.toMap(Evidence::evidenceId, item -> item));
        List<String> kept = diagnosis.supportingEvidence().stream().filter(id -> {
            Evidence item = byId.get(id);
            return item != null && item.isCurrentAt(evaluatedAt)
                && item.collectionStatus() == Evidence.CollectionStatus.OBSERVED
                && item.fact().path("success").asBoolean(false);
        }).toList();
        List<String> excluded = diagnosis.supportingEvidence().stream()
            .filter(id -> !kept.contains(id)).toList();
        if (excluded.isEmpty()) return diagnosis;
        List<String> missing = new ArrayList<>(diagnosis.missingEvidence());
        missing.add("Excluded stale, failed, or unknown supporting evidence: "
            + String.join(",", excluded));
        return new Diagnosis(diagnosis.rootCauseCode(), diagnosis.confidence(), kept,
            diagnosis.contradictingEvidence(), diagnosis.alternatives(), missing,
            diagnosis.recommendedActionCode(), diagnosis.claimedResolved(),
            diagnosis.schemaVersion(), diagnosis.diagnosisStatus(), diagnosis.currentCondition(),
            diagnosis.evaluatedAt(), diagnosis.resolutionAttribution());
    }

    private static void normalizeEvidenceIds(
        com.fasterxml.jackson.databind.node.ObjectNode root, String field
    ) {
        var values = root.path(field);
        if (!values.isArray()) return;
        var normalized = MAPPER.createArrayNode();
        for (var value : values) {
            if (value.isTextual()) {
                normalized.add(value.textValue());
            } else if (value.isObject() && value.path("evidenceId").isTextual()) {
                normalized.add(value.path("evidenceId").textValue());
            } else {
                normalized.add(value);
            }
        }
        root.set(field, normalized);
    }

    private static void normalizeObjectStrings(
        com.fasterxml.jackson.databind.node.ObjectNode root, String field, String objectField
    ) {
        var values = root.path(field);
        if (!values.isArray()) return;
        var normalized = MAPPER.createArrayNode();
        for (var value : values) {
            if (value.isTextual()) normalized.add(value.textValue());
            else if (value.isObject() && value.path(objectField).isTextual()) {
                normalized.add(value.path(objectField).textValue());
            } else normalized.add(value);
        }
        root.set(field, normalized);
    }

    private static String runIdFromReference(String reference) {
        if (reference == null || !reference.startsWith("run://")) return null;
        int end = reference.indexOf('/', 6);
        return end < 0 ? null : reference.substring(6, end);
    }

    static LLMConfig modelConfig(Map<String, String> env) {
        String apiKey = required(env, "CLAWKIT_API_KEY");
        LLMConfig.Builder builder = LLMConfig.builder()
            .apiKey(apiKey)
            .protocol(LLMConfig.Protocol.OPENAI_COMPAT)
            .requestTimeout(Duration.ofSeconds(90))
            .maxRetries(2);
        String model = env.get("CLAWKIT_MODEL");
        if (model != null && !model.isBlank()) builder.model(model.trim());
        return builder.build();
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing environment variable: " + name);
        return value;
    }

}
