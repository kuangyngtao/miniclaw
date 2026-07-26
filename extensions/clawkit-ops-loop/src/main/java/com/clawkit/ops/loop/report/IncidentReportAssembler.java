package com.clawkit.ops.loop.report;

import com.clawkit.ops.loop.Diagnosis;
import com.clawkit.ops.loop.DiscoveryResult;
import com.clawkit.ops.loop.Evidence;
import com.clawkit.ops.loop.RemoteIncidentResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic assembler that builds a {@link HumanIncidentReport} from
 * a {@link RemoteIncidentResult}.
 *
 * <p>M2-5. All facts are derived deterministically from Discovery and
 * Diagnosis. DeepSeek free text is captured in summary/impact but never
 * rewrites metrics, statuses, times, evidence, or error codes.
 */
public final class IncidentReportAssembler {

    private static final DateTimeFormatter ISO = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final String SCHEMA_VERSION = "1";
    private static final String DATA_LABEL = "SYNTHETIC_BUSINESS_DATA";

    private IncidentReportAssembler() {}

    public static HumanIncidentReport assemble(RemoteIncidentResult result) {
        DiscoveryResult discovery = result.discovery();
        Diagnosis diagnosis = result.diagnosis();
        List<Evidence> allEvidence = discovery.bundle().evidence();
        Instant now = result.completedAt();

        // ── Status mapping ──
        HumanIncidentReport.IncidentStatus status = mapStatus(diagnosis);
        HumanIncidentReport.DiagnosisConfidence confidence = mapConfidence(diagnosis);

        // ── Evidence views ──
        List<HumanIncidentReport.EvidenceView> supporting = buildViews(
            findEvidence(allEvidence, diagnosis.supportingEvidence()));
        List<HumanIncidentReport.EvidenceView> contradicting = buildViews(
            findEvidence(allEvidence, diagnosis.contradictingEvidence()));

        // Missing: mentioned in diagnosis but not in evidence bundle
        List<HumanIncidentReport.EvidenceView> missing = diagnosis.missingEvidence().stream()
            .map(id -> new HumanIncidentReport.EvidenceView(
                id, "unknown", "unknown", "missing", "stale",
                "Evidence not collected", null, null))
            .toList();

        // Failed or stale
        List<HumanIncidentReport.EvidenceView> failedOrStale = allEvidence.stream()
            .filter(e -> e.collectionStatus() == Evidence.CollectionStatus.COLLECTION_FAILED
                || (e.validUntil() != null && e.validUntil().isBefore(now)))
            .map(e -> viewFrom(e))
            .toList();

        // ── Timeline ──
        List<HumanIncidentReport.TimelineEntry> timeline = buildTimeline(
            discovery, allEvidence, result.completedAt());

        // ── Actions ──
        List<String> actions = buildActions(diagnosis);
        boolean needsEscalation = diagnosis.recommendedActionCode() != null
            && diagnosis.recommendedActionCode().contains("ESCALATE");
        String escalationReason = needsEscalation
            ? "Diagnosis " + diagnosis.diagnosisStatus() + " with confidence " + diagnosis.confidence()
            : null;

        // ── Summary and Impact ──
        String summary = buildSummary(discovery, diagnosis, result);
        String impact = buildImpact(discovery, diagnosis);
        String currentCondition = diagnosis.currentCondition().name();

        // ── Build report ──
        HumanIncidentReport report = new HumanIncidentReport(
            SCHEMA_VERSION, "1", null, DATA_LABEL,
            discovery.incidentId(), discovery.incidentId(), discovery.profileName(),
            discovery.completedAt(), result.completedAt(),
            status, confidence, diagnosis.rootCauseCode(), diagnosis.confidence(),
            summary, impact, currentCondition,
            supporting, contradicting, missing, failedOrStale, timeline,
            actions, needsEscalation, escalationReason,
            diagnosis.claimedResolved()
        );

        // Compute content hash
        String hash = computeHash(report);
        return new HumanIncidentReport(
            report.schemaVersion(), report.reportVersion(), hash,
            report.dataLabel(), report.incidentId(), report.logicalTargetId(),
            report.profileName(), report.discoveredAt(), report.completedAt(),
            report.status(), report.diagnosisConfidence(),
            report.rootCauseCode(), report.confidence(),
            report.summary(), report.businessImpact(), report.currentCondition(),
            report.supportingEvidence(), report.contradictingEvidence(),
            report.missingEvidence(), report.failedOrStaleEvidence(),
            report.timeline(), report.recommendedActions(),
            report.requiresHumanEscalation(), report.escalationReason(),
            report.claimedResolved()
        );
    }

    // ── Mappings ──

    static HumanIncidentReport.IncidentStatus mapStatus(Diagnosis d) {
        return switch (d.currentCondition()) {
            case ACTIVE -> HumanIncidentReport.IncidentStatus.ACTIVE;
            case RECOVERED -> HumanIncidentReport.IncidentStatus.RECOVERED;
            case UNKNOWN -> HumanIncidentReport.IncidentStatus.UNKNOWN;
        };
    }

    static HumanIncidentReport.DiagnosisConfidence mapConfidence(Diagnosis d) {
        return switch (d.diagnosisStatus()) {
            case CONFIRMED -> HumanIncidentReport.DiagnosisConfidence.CONFIRMED;
            case PROBABLE -> HumanIncidentReport.DiagnosisConfidence.PROBABLE;
            case INCONCLUSIVE -> HumanIncidentReport.DiagnosisConfidence.INCONCLUSIVE;
        };
    }

    // ── Evidence views ──

    static List<Evidence> findEvidence(List<Evidence> all, List<String> ids) {
        return ids.stream()
            .flatMap(id -> all.stream().filter(e -> e.evidenceId().equals(id)))
            .toList();
    }

    static List<HumanIncidentReport.EvidenceView> buildViews(List<Evidence> evidence) {
        return evidence.stream().map(IncidentReportAssembler::viewFrom).toList();
    }

    static HumanIncidentReport.EvidenceView viewFrom(Evidence e) {
        return new HumanIncidentReport.EvidenceView(
            e.evidenceId(),
            e.type().name(),
            sanitizeScope(e.scope()),
            e.collectionStatus().name(),
            e.freshness().name(),
            summarizeFact(e.fact()),
            e.observedAt(),
            e.validUntil()
        );
    }

    static String summarizeFact(JsonNode fact) {
        if (fact == null || fact.isNull()) return "no data";
        StringBuilder sb = new StringBuilder();
        if (fact.has("success")) {
            sb.append(fact.get("success").asBoolean() ? "ok" : "failed");
        }
        if (fact.has("errorCode") && !fact.get("errorCode").isNull()) {
            sb.append(" (").append(fact.get("errorCode").asText()).append(")");
        }
        if (fact.has("data") && !fact.get("data").isNull()) {
            JsonNode data = fact.get("data");
            if (data.has("State")) sb.append(" state=").append(data.get("State").asText());
            else if (data.has("status")) sb.append(" ").append(data.get("status").asText());
            else sb.append(" data_present");
        }
        if (sb.isEmpty()) sb.append("no summary");
        return sb.toString();
    }

    static String sanitizeScope(String scope) {
        if (scope == null) return "unknown";
        // Remove host/ip/port/path fragments
        return scope.replaceAll("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "<host>")
            .replaceAll(":\\d{2,5}", ":<port>");
    }

    // ── Timeline ──

    static List<HumanIncidentReport.TimelineEntry> buildTimeline(
        DiscoveryResult discovery, List<Evidence> evidence, Instant completedAt) {
        List<HumanIncidentReport.TimelineEntry> timeline = new ArrayList<>();
        timeline.add(new HumanIncidentReport.TimelineEntry(
            discovery.completedAt(), "Discovery completed",
            "status=" + discovery.status() + " evidence="
                + discovery.requiredSuccess() + "/" + discovery.requiredTotal()));

        for (Evidence e : evidence.stream()
            .sorted(Comparator.comparing(Evidence::observedAt))
            .toList()) {
            timeline.add(new HumanIncidentReport.TimelineEntry(
                e.observedAt(), "Evidence collected: " + e.evidenceId(),
                e.type() + " / " + e.collectionStatus()));
        }

        timeline.add(new HumanIncidentReport.TimelineEntry(
            completedAt, "Report assembled", ""));
        return timeline;
    }

    // ── Actions ──

    static List<String> buildActions(Diagnosis diagnosis) {
        List<String> actions = new ArrayList<>();
        if (diagnosis.recommendedActionCode() != null
            && !"ESCALATE".equals(diagnosis.recommendedActionCode())) {
            actions.add(diagnosis.recommendedActionCode());
        }
        if (diagnosis.missingEvidence() != null && !diagnosis.missingEvidence().isEmpty()) {
            actions.add("Collect missing evidence: " + String.join(", ", diagnosis.missingEvidence()));
        }
        if (diagnosis.diagnosisStatus() != Diagnosis.DiagnosisStatus.CONFIRMED) {
            actions.add("Review diagnosis: " + diagnosis.diagnosisStatus() + " (confidence: "
                + String.format("%.0f%%", diagnosis.confidence() * 100) + ")");
        }
        actions.add("ESCALATE_TO_HUMAN");
        return actions;
    }

    // ── Summary / Impact ──

    static String buildSummary(DiscoveryResult discovery, Diagnosis diagnosis,
                               RemoteIncidentResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("[SYNTHETIC_BUSINESS_DATA] ");
        sb.append("Incident ").append(discovery.incidentId()).append(" on profile ")
            .append(discovery.profileName()).append(". ");
        sb.append("Diagnosis: ").append(diagnosis.rootCauseCode())
            .append(" (").append(diagnosis.diagnosisStatus())
            .append(", confidence: ").append(String.format("%.0f%%", diagnosis.confidence() * 100))
            .append("). ");
        if (!result.providerCalled()) {
            sb.append("Provider was NOT called (")
                .append(result.diagnosisFailureCode()).append("). ");
        }
        sb.append("Condition: ").append(diagnosis.currentCondition()).append(".");
        return sb.toString();
    }

    static String buildImpact(DiscoveryResult discovery, Diagnosis diagnosis) {
        return "Profile " + discovery.profileName()
            + " — Root cause: " + diagnosis.rootCauseCode()
            + " — Status: " + diagnosis.currentCondition()
            + " — Confidence: " + String.format("%.0f%%", diagnosis.confidence() * 100);
    }

    // ── Hash ──

    static String computeHash(HumanIncidentReport report) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(report.incidentId().getBytes(StandardCharsets.UTF_8));
            md.update(report.reportVersion().getBytes(StandardCharsets.UTF_8));
            md.update(report.rootCauseCode().getBytes(StandardCharsets.UTF_8));
            md.update(Double.toString(report.confidence()).getBytes(StandardCharsets.UTF_8));
            md.update(report.status().name().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest(), 0, 8);
        } catch (Exception e) {
            return "error";
        }
    }
}
