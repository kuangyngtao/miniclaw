package com.clawkit.ops.loop.report;

import com.clawkit.ops.loop.Diagnosis;
import com.clawkit.ops.loop.DiscoveryStatus;

import java.time.Instant;
import java.util.List;

/**
 * Unified human-readable incident report model.
 *
 * <p>M2-5. All renderers (Markdown, JSON, Feishu) consume this single
 * presentation model. Facts are determined by deterministic code only;
 * DeepSeek free text must NOT rewrite any fields.
 */
public record HumanIncidentReport(
    // ── Header ──
    String schemaVersion,
    String reportVersion,
    String contentHash,
    String dataLabel,          // always "SYNTHETIC_BUSINESS_DATA"
    String incidentId,
    String logicalTargetId,
    String profileName,
    Instant discoveredAt,
    Instant completedAt,

    // ── Status ──
    IncidentStatus status,     // ACTIVE / RECOVERED / UNKNOWN
    DiagnosisConfidence diagnosisConfidence, // CONFIRMED / PROBABLE / INCONCLUSIVE
    String rootCauseCode,
    double confidence,

    // ── Content ──
    String summary,            // one-paragraph executive summary
    String businessImpact,     // business-level impact description
    String currentCondition,   // current system condition
    List<EvidenceView> supportingEvidence,
    List<EvidenceView> contradictingEvidence,
    List<EvidenceView> missingEvidence,
    List<EvidenceView> failedOrStaleEvidence,
    List<TimelineEntry> timeline,

    // ── Actions ──
    List<String> recommendedActions,
    boolean requiresHumanEscalation,
    String escalationReason,

    // ── Integrity ──
    boolean claimedResolved
) {
    public enum IncidentStatus { ACTIVE, RECOVERED, UNKNOWN }
    public enum DiagnosisConfidence { CONFIRMED, PROBABLE, INCONCLUSIVE }

    /** Evidence view suitable for human consumption — no raw JSON. */
    public record EvidenceView(
        String evidenceId,
        String type,
        String scope,
        String status,
        String freshness,
        String summary,        // one-line human-readable fact summary
        Instant observedAt,
        Instant validUntil
    ) {}

    /** Timeline entry for reconstructing the incident chronology. */
    public record TimelineEntry(
        Instant timestamp,
        String event,
        String detail
    ) {}
}
