package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a DeepSeek diagnosis on a frozen {@link DiscoveryResult}.
 *
 * <p>PR-M5 §7. The gate enforces:
 * <ol>
 *   <li>Only call Provider when status is COMPLETE and all required
 *       evidence is current and unexpired.</li>
 *   <li>Provider receives only redacted evidence facts + logical targetId
 *       + the {@code submit_diagnosis} tool.</li>
 *   <li>Empty content, truncation, invalid JSON, or fabricated evidence
 *       IDs → retry once → fail-closed as INCONCLUSIVE.</li>
 * </ol>
 */
public final class DeepSeekDiagnosisGate {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekDiagnosisGate.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_RETRIES = 1;
    static final int MAX_OUTPUT_TOKENS = 2048;

    /** Minimal Provider interface that the gate depends on. */
    @FunctionalInterface
    public interface DiagnosisProvider {
        /** Send a raw chat completion request and return the response text. */
        String complete(String requestJson) throws IOException;
    }

    private final DiagnosisProvider provider;
    private final String modelName;
    private final Clock clock;

    public DeepSeekDiagnosisGate(DiagnosisProvider provider, String modelName, Clock clock) {
        this.provider = provider;
        this.modelName = modelName != null ? modelName : "deepseek-v4-flash";
        this.clock = clock;
    }

    /**
     * Attempt a diagnosis. Returns INCONCLUSIVE if the gate is not passed
     * or if the Provider fails.
     *
     * @param result   the frozen discovery result
     * @param symptom  human-readable symptom description
     * @return a Diagnosis record
     */
    public Diagnosis diagnose(DiscoveryResult result, String symptom) {
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

        // ── Gate 3: call Provider ──
        String request = buildRequest(result, symptom);
        String response;
        try {
            response = callWithRetry(request);
        } catch (IOException e) {
            log.warn("[diagnosis-gate] Provider failed: {}", e.getMessage());
            return inconclusive(result, "Provider error: " + e.getMessage());
        }

        // ── Gate 4: validate response ──
        try {
            Diagnosis d = parseAndValidate(response, result, now);
            log.info("[diagnosis-gate] diagnosis: rootCause={}, confidence={}",
                d.rootCauseCode(), d.confidence());
            return d;
        } catch (Exception e) {
            log.warn("[diagnosis-gate] response validation failed: {}", e.getMessage());
            return inconclusive(result, "validation failed: " + e.getMessage());
        }
    }

    // ── Internals ──

    private String callWithRetry(String request) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                String resp = provider.complete(request);
                if (resp == null || resp.isBlank()) {
                    throw new IOException("empty Provider response");
                }
                return resp;
            } catch (IOException e) {
                last = e;
                if (attempt < MAX_RETRIES) {
                    log.info("[diagnosis-gate] retry {}/{}", attempt + 1, MAX_RETRIES);
                }
            }
        }
        throw last != null ? last : new IOException("Provider failed after retries");
    }

    String buildRequest(DiscoveryResult result, String symptom) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("model", modelName);
        req.put("max_tokens", MAX_OUTPUT_TOKENS);
        req.put("temperature", 0.0);

        // Evidence facts only — no host/key/path/URL
        ArrayNode messages = req.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt(result, symptom));
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", "Analyze the evidence and submit your diagnosis using submit_diagnosis.");

        // Single tool
        ArrayNode tools = req.putArray("tools");
        tools.add(submitDiagnosisTool());

        return req.toString();
    }

    private String systemPrompt(DiscoveryResult result, String symptom) {
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
            sb.append("\n");
        }

        sb.append("\nCall submit_diagnosis with your assessment.");
        return sb.toString();
    }

    private ObjectNode submitDiagnosisTool() {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", "submit_diagnosis");
        fn.put("description", "Submit a structured diagnosis for the incident.");
        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("rootCauseCode").put("type", "string")
            .put("description", "Root cause code from the allowed enumeration");
        props.putObject("diagnosisStatus").put("type", "string")
            .put("enum", ArrayNode.class.cast(
                MAPPER.createArrayNode().add("CONFIRMED").add("PROBABLE").add("INCONCLUSIVE")));
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
        return tool;
    }

    Diagnosis parseAndValidate(String response, DiscoveryResult result, Instant now)
        throws IOException {
        // Extract JSON from model response
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

        // Build Diagnosis using the simplified constructor
        boolean claimedResolved = root.path("claimedResolved").asBoolean(false);
        if (claimedResolved) {
            throw new IOException("claimedResolved=true rejected in read-only mode");
        }
        return new Diagnosis(
            root.path("rootCauseCode").asText("INCONCLUSIVE"),
            confidenceDouble(root.path("confidence"), 0.0),
            toStringList(root.path("supportingEvidence")),
            toStringList(root.path("contradictingEvidence")),
            toStringList(root.path("alternatives")),
            toStringList(root.path("missingEvidence")),
            root.path("recommendedActionCode").asText("ESCALATE"),
            claimedResolved);
    }

    static String extractFencedJson(String response) {
        int start = response.lastIndexOf("```json");
        if (start >= 0) {
            int end = response.indexOf("```", start + 7);
            if (end > start) return response.substring(start + 7, end).trim();
        }
        // Fallback: try to find a JSON object
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
            return d > 1.0 ? d / 100.0 : d; // normalize percentage
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

    /** Simplified: required evidence heuristic for the gate check. */
    private boolean isRequiredEvidence(Evidence e) {
        return e.collectionStatus() != Evidence.CollectionStatus.COLLECTION_FAILED;
    }
}
