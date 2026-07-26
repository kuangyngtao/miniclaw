package com.clawkit.ops.loop.report;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Renders a {@link HumanIncidentReport} as human-readable Markdown.
 *
 * <p>M2-5. Uses the same presentation model as JSON and Feishu renderers.
 */
public final class MarkdownIncidentRenderer {

    private static final DateTimeFormatter DT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private MarkdownIncidentRenderer() {}

    public static String render(HumanIncidentReport report) {
        StringBuilder md = new StringBuilder();

        md.append("# Incident Report\n\n");
        md.append("> **").append(report.dataLabel()).append("**\n\n");

        md.append("| Field | Value |\n|-------|-------|\n");
        md.append("| Incident ID | `").append(report.incidentId()).append("` |\n");
        md.append("| Profile | ").append(report.profileName()).append(" |\n");
        md.append("| Status | **").append(report.status()).append("** |\n");
        md.append("| Diagnosis | ").append(report.diagnosisConfidence())
            .append(" → ").append(report.rootCauseCode())
            .append(" (").append(String.format("%.0f%%", report.confidence() * 100)).append(") |\n");
        md.append("| Condition | ").append(report.currentCondition()).append(" |\n\n");

        md.append("## Summary\n\n").append(report.summary()).append("\n\n");

        md.append("## Business Impact\n\n").append(report.businessImpact()).append("\n\n");

        if (!report.supportingEvidence().isEmpty()) {
            md.append("## Supporting Evidence\n\n");
            for (var e : report.supportingEvidence()) {
                md.append("- **").append(e.evidenceId()).append("** (").append(e.type())
                    .append("): ").append(e.summary()).append("\n");
            }
            md.append("\n");
        }

        if (!report.contradictingEvidence().isEmpty()) {
            md.append("## Contradicting Evidence\n\n");
            for (var e : report.contradictingEvidence()) {
                md.append("- **").append(e.evidenceId()).append("**: ").append(e.summary()).append("\n");
            }
            md.append("\n");
        }

        if (!report.failedOrStaleEvidence().isEmpty()) {
            md.append("## Failed / Stale Evidence\n\n");
            for (var e : report.failedOrStaleEvidence()) {
                md.append("- **").append(e.evidenceId()).append("** (").append(e.status())
                    .append("): ").append(e.summary()).append("\n");
            }
            md.append("\n");
        }

        if (!report.missingEvidence().isEmpty()) {
            md.append("## Missing Evidence\n\n");
            for (var e : report.missingEvidence()) {
                md.append("- ").append(e.evidenceId()).append("\n");
            }
            md.append("\n");
        }

        md.append("## Timeline\n\n");
        for (var t : report.timeline()) {
            md.append("- `").append(DT.format(t.timestamp())).append("` ")
                .append(t.event());
            if (t.detail() != null && !t.detail().isEmpty()) {
                md.append(" — ").append(t.detail());
            }
            md.append("\n");
        }
        md.append("\n");

        md.append("## Recommended Actions\n\n");
        for (String a : report.recommendedActions()) {
            md.append("- ").append(a).append("\n");
        }
        md.append("\n");

        if (report.requiresHumanEscalation()) {
            md.append("## ⚠️ Human Escalation Required\n\n")
                .append(report.escalationReason()).append("\n\n");
        }

        md.append("---\n");
        md.append("reportVersion: ").append(report.reportVersion())
            .append(" | hash: `").append(report.contentHash()).append("`\n");

        return md.toString();
    }
}
