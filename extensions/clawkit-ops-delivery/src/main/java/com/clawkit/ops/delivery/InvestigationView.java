package com.clawkit.ops.delivery;

import java.time.Instant;
import java.util.List;

/**
 * User-facing investigation result. Must contain no secrets, hashes,
 * SSH argv, raw logs, or Java stack traces.
 *
 * <p>OPS-PRODUCT-LOOP-1 §5.5.
 */
public record InvestigationView(
    String incidentId,
    String targetId,
    String serviceId,
    UserIncidentStatus status,
    /** One-sentence Chinese summary of what happened. */
    String summary,
    /** Key facts discovered (Chinese labels). */
    List<String> observedFacts,
    /** Root cause in Chinese, or "无法确定". */
    String diagnosis,
    /** Repair recommendation or "无需操作". */
    String recommendation,
    /** Whether user approval is required and waiting. */
    boolean approvalRequired,
    /** What was executed, or empty if no repair. */
    String actionExecuted,
    /** Verification result summary in Chinese. */
    String verificationSummary,
    /** What the user should do next (Chinese). */
    String nextAction,
    Instant createdAt,
    Instant updatedAt,
    /** Relative path to evidence directory under .clawkit/incidents/. */
    String evidenceDirectory
) {
    public InvestigationView {
        if (incidentId == null || incidentId.isBlank())
            throw new IllegalArgumentException("incidentId required");
        if (status == null) throw new IllegalArgumentException("status required");
        summary = summary != null ? summary : "";
        observedFacts = observedFacts != null ? List.copyOf(observedFacts) : List.of();
        diagnosis = diagnosis != null ? diagnosis : "未完成诊断";
        recommendation = recommendation != null ? recommendation : "";
        actionExecuted = actionExecuted != null ? actionExecuted : "";
        verificationSummary = verificationSummary != null ? verificationSummary : "";
        nextAction = nextAction != null ? nextAction : "";
    }
}
