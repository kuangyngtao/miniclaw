package com.clawkit.ops.loop;

import com.clawkit.provider.LLMException;
import com.clawkit.provider.LLMProvider;
import com.clawkit.provider.ModelParameters;
import com.clawkit.provider.ModelRequest;
import com.clawkit.provider.ModelResponse;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a DeepSeek diagnosis on a frozen {@link DiscoveryResult}.
 *
 * <p>M2-0: Refactored to use {@link LLMProvider} (with its built-in HTTP,
 * auth, retry, timeout, and circuit-breaker) instead of raw JSON strings.
 *
 * <p>The gate enforces:
 * <ol>
 *   <li>Only call Provider when status is COMPLETE and all required
 *       evidence is current and unexpired.</li>
 *   <li>Provider receives only redacted evidence facts + logical targetId
 *       + the {@code submit_diagnosis} tool definition.</li>
 *   <li>Empty content, truncation, invalid JSON, or fabricated evidence
 *       IDs → retry once at gate level → fail-closed as INCONCLUSIVE.</li>
 *   <li>{@link ExecutionControl} deadline propagates through to the Provider.</li>
 * </ol>
 */
public final class DeepSeekDiagnosisGate {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekDiagnosisGate.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_CONTENT_RETRIES = 1;
    static final int MAX_OUTPUT_TOKENS = 2048;

    private final LLMProvider provider;
    private final String modelName;
    private final Clock clock;

    /**
     * Create a gate backed by a typed {@link LLMProvider}.
     * HTTP, auth, retry, timeout, and circuit-breaking are handled by the Provider.
     */
    public DeepSeekDiagnosisGate(LLMProvider provider, String modelName, Clock clock) {
        this.provider = provider;
        this.modelName = modelName != null ? modelName : "deepseek-v4-flash";
        this.clock = clock;
    }

    /**
     * Attempt a diagnosis with an explicit deadline.
     *
     * @param result   the frozen discovery result
     * @param symptom  human-readable symptom description
     * @param control  execution control carrying at least a deadline
     * @return a Diagnosis record
     */
    public Diagnosis diagnose(DiscoveryResult result, String symptom, ExecutionControl control) {
        // ── Gate 1: only COMPLETE ──
        if (result.status() != DiscoveryStatus.COMPLETE) {
            log.info("[diagnosis-gate] status={} — skipping Provider", result.status());
            return inconclusive(result, "discovery status is " + result.status());
        }

        // ── Gate 2: evidence must be current + unexpired ──
        Instant now = clock.instant();
        List<Evidence> all = result.bundle().evidence();
        long staleCount = all.stream().filter(e -> !e.isCurrentAt(now)).count();
        long failedCount = all.stream()
            .filter(e -> e.collectionStatus() == Evidence.CollectionStatus.COLLECTION_FAILED)
            .count();

        if (staleCount > 0 || failedCount == all.size()) {
            log.info("[diagnosis-gate] stale={} failed={}/{} — skipping Provider",
                staleCount, failedCount, all.size());
            return inconclusive(result,
                "evidence not current (stale=" + staleCount + " failed=" + failedCount + ")");
        }

        // ── Gate 3: call Provider with typed request ──
        try {
            return callWithContentRetry(result, symptom, control);
        } catch (LLMException e) {
            log.warn("[diagnosis-gate] Provider failed: {}", e.getMessage());
            return inconclusive(result, "Provider error: " + e.getMessage());
        }
    }

    /**
     * Attempt a diagnosis with a simple duration-based deadline.
     */
    public Diagnosis diagnose(DiscoveryResult result, String symptom, Duration deadline) {
        return diagnose(result, symptom, new DeadlineControl(deadline, clock.instant()));
    }

    /**
     * Attempt a diagnosis with no explicit deadline (uses Provider defaults).
     */
    public Diagnosis diagnose(DiscoveryResult result, String symptom) {
        return diagnose(result, symptom, ExecutionControl.none());
    }

    // ── Internals ──

    /**
     * Call Provider with gate-level retry for content/validation failures.
     * HTTP-level failures are handled by the Provider's own retry and
     * surface as {@link LLMException} — those are NOT retried here.
     */
    private Diagnosis callWithContentRetry(DiscoveryResult result, String symptom,
                                           ExecutionControl control) {
        Exception lastFailure = null;
        for (int attempt = 0; attempt <= MAX_CONTENT_RETRIES; attempt++) {
            try {
                ModelRequest request = buildRequest(result, symptom, control);
                ModelResponse response = provider.generate(request);

                String content = response.content();
                if (content == null || content.isBlank()) {
                    lastFailure = new IOException("empty Provider response");
                    log.info("[diagnosis-gate] empty response, retry {}/{}",
                        attempt + 1, MAX_CONTENT_RETRIES);
                    continue;
                }

                Diagnosis d = parseAndValidate(content, result, now());
                log.info("[diagnosis-gate] diagnosis: rootCause={}, confidence={}",
                    d.rootCauseCode(), d.confidence());
                return d;

            } catch (LLMException e) {
                // Provider-level failure (HTTP/auth/timeout) — do not retry at gate level
                throw e;
            } catch (Exception e) {
                lastFailure = e;
                if (attempt < MAX_CONTENT_RETRIES) {
                    log.info("[diagnosis-gate] content validation failed ({}), retry {}/{}",
                        e.getMessage(), attempt + 1, MAX_CONTENT_RETRIES);
                }
            }
        }
        log.warn("[diagnosis-gate] content retries exhausted: {}",
            lastFailure != null ? lastFailure.getMessage() : "unknown");
        return inconclusive(result, "validation failed after retries: "
            + (lastFailure != null ? lastFailure.getMessage() : "unknown"));
    }

    private Instant now() {
        return clock.instant();
    }

    ModelRequest buildRequest(DiscoveryResult result, String symptom, ExecutionControl control) {
        List<Message> messages = buildMessages(result, symptom);
        List<ToolDefinition> tools = List.of(submitDiagnosisToolDef());
        ModelParameters params = new ModelParameters(0.0, MAX_OUTPUT_TOKENS, false);
        return new ModelRequest(messages, tools, params, control);
    }

    List<Message> buildMessages(DiscoveryResult result, String symptom) {
        return List.of(
            Message.system(systemPromptText(result, symptom)),
            Message.user("Analyze the evidence and submit your diagnosis using submit_diagnosis.")
        );
    }

    private String systemPromptText(DiscoveryResult result, String symptom) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an SRE diagnosing an incident.\n");
        sb.append("Target: ").append(result.incidentId()).append("\n");
        sb.append("Symptom: ").append(symptom != null ? symptom : "unspecified").append("\n");
        sb.append("Profile: ").append(result.profileName()).append("\n\n");
        sb.append("Evidence:\n");

        for (Evidence e : result.bundle().evidence()) {
            sb.append("- [").append(e.evidenceId()).append("] ")
                .append(e.type()).append(" / ").append(e.scope())
                .append(" status=").append(e.collectionStatus())
                .append(" freshness=").append(e.freshness());
            if (e.validUntil() != null) {
                sb.append(" validUntil=").append(e.validUntil());
            }
            if (e.fact() != null && !e.fact().isEmpty()) {
                try {
                    String compact = MAPPER.writeValueAsString(e.fact());
                    if (compact.length() > 2048) compact = compact.substring(0, 2048) + "...";
                    sb.append("\n  facts: ").append(compact);
                } catch (Exception ignored) {}
            }
            sb.append("\n");
        }

        sb.append("\nCall submit_diagnosis with your assessment.");
        return sb.toString();
    }

    ToolDefinition submitDiagnosisToolDef() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("rootCauseCode").put("type", "string")
            .put("description", "Root cause code from the allowed enumeration");
        props.putObject("diagnosisStatus").put("type", "string")
            .put("enum", MAPPER.createArrayNode().add("CONFIRMED").add("PROBABLE").add("INCONCLUSIVE"));
        props.putObject("currentCondition").put("type", "string");
        props.putObject("confidence").put("type", "number")
            .put("minimum", 0).put("maximum", 1);
        props.putObject("supportingEvidence").put("type", "array")
            .putObject("items").put("type", "string");
        props.putObject("contradictingEvidence").put("type", "array")
            .putObject("items").put("type", "string");
        props.putObject("alternatives").put("type", "array")
            .putObject("items").put("type", "string");
        props.putObject("missingEvidence").put("type", "array")
            .putObject("items").put("type", "string");
        props.putObject("recommendedActionCode").put("type", "string");
        props.putObject("claimedResolved").put("type", "boolean");
        params.putArray("required")
            .add("rootCauseCode").add("diagnosisStatus").add("confidence")
            .add("supportingEvidence").add("contradictingEvidence");
        return new ToolDefinition("submit_diagnosis",
            "Submit a structured diagnosis for the incident.", params);
    }

    Diagnosis parseAndValidate(String response, DiscoveryResult result, Instant now)
        throws IOException {
        String json = extractFencedJson(response);

        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IOException("invalid JSON in diagnosis response");
        }

        // Validate evidence IDs exist
        List<String> evidenceIds = result.bundle().evidence().stream()
            .map(Evidence::evidenceId).toList();
        validateReferences(root.path("supportingEvidence"), evidenceIds, "supportingEvidence");
        validateReferences(root.path("contradictingEvidence"), evidenceIds, "contradictingEvidence");

        // Build Diagnosis
        boolean claimedResolved = root.path("claimedResolved").asBoolean(false);
        if (claimedResolved) {
            throw new IOException("claimedResolved=true rejected in read-only mode");
        }

        Diagnosis.DiagnosisStatus diagStatus;
        try {
            diagStatus = Diagnosis.DiagnosisStatus.valueOf(
                root.path("diagnosisStatus").asText("INCONCLUSIVE"));
        } catch (IllegalArgumentException e) {
            diagStatus = Diagnosis.DiagnosisStatus.INCONCLUSIVE;
        }

        return new Diagnosis(
            root.path("rootCauseCode").asText("INCONCLUSIVE"),
            confidenceDouble(root.path("confidence"), 0.0),
            toStringList(root.path("supportingEvidence")),
            toStringList(root.path("contradictingEvidence")),
            toStringList(root.path("alternatives")),
            toStringList(root.path("missingEvidence")),
            root.path("recommendedActionCode").asText("ESCALATE"),
            claimedResolved,
            "1",
            diagStatus,
            Diagnosis.CurrentCondition.UNKNOWN,
            now,
            Diagnosis.ResolutionAttribution.NONE);
    }

    static String extractFencedJson(String response) {
        int start = response.lastIndexOf("```json");
        if (start >= 0) {
            int end = response.indexOf("```", start + 7);
            if (end > start) return response.substring(start + 7, end).trim();
        }
        int brace = response.indexOf('{');
        int lastBrace = response.lastIndexOf('}');
        if (brace >= 0 && lastBrace > brace) {
            return response.substring(brace, lastBrace + 1);
        }
        return response;
    }

    private void validateReferences(JsonNode ids, List<String> validIds, String field)
        throws IOException {
        if (ids == null || !ids.isArray()) return;
        for (JsonNode id : ids) {
            if (!validIds.contains(id.asText())) {
                throw new IOException("fabricated evidence ID in " + field + ": " + id.asText());
            }
        }
    }

    private static List<String> toStringList(JsonNode array) {
        if (array == null || !array.isArray()) return List.of();
        List<String> result = new java.util.ArrayList<>();
        for (JsonNode n : array) result.add(n.asText());
        return List.copyOf(result);
    }

    private static double confidenceDouble(JsonNode v, double def) {
        if (v == null) return def;
        if (v.isNumber()) {
            double d = v.doubleValue();
            return d > 1.0 ? d / 100.0 : d;
        }
        if (v.isTextual()) {
            return switch (v.asText().toUpperCase()) {
                case "HIGH", "CONFIRMED" -> 0.9;
                case "PROBABLE" -> 0.7;
                case "LOW" -> 0.3;
                default -> def;
            };
        }
        return def;
    }

    private Diagnosis inconclusive(DiscoveryResult result, String reason) {
        return new Diagnosis("INCONCLUSIVE", 0.0,
            List.of(), List.of(), List.of(), List.of(),
            "ESCALATE", false);
    }
}
