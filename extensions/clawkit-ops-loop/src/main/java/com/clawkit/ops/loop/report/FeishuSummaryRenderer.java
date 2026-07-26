package com.clawkit.ops.loop.report;

/**
 * Renders a compact Feishu-compatible summary from {@link HumanIncidentReport}.
 *
 * <p>M2-5. Uses the same presentation model as Markdown and JSON renderers.
 * Designed for a single Feishu message — no interactive cards, no approvals.
 * Contains ONLY: incidentId, logical targetId, SYNTHETIC_BUSINESS_DATA label,
 * impact metrics, status codes, P95, failure rate, root cause code,
 * diagnosis status, confidence, evidence IDs, read-only next steps.
 *
 * <p>NEVER contains: SSH host/user/key, DB URL/password, appId/appSecret,
 * chat_id, message_id, control tokens, Case manifest, Ground Truth,
 * full logs, order IDs, account IDs, local file paths.
 */
public final class FeishuSummaryRenderer {

    private FeishuSummaryRenderer() {}

    public static String render(HumanIncidentReport report) {
        StringBuilder sb = new StringBuilder();

        sb.append("🔍 **Incident Report**\\n\\n");
        sb.append("> ⚠️ ").append(report.dataLabel()).append("\\n\\n");

        sb.append("**ID:** `").append(report.incidentId()).append("`\\n");
        sb.append("**Profile:** ").append(report.profileName()).append("\\n");
        sb.append("**Status:** ").append(statusEmoji(report.status()))
            .append(" ").append(report.status()).append("\\n");
        sb.append("**Diagnosis:** ").append(report.diagnosisConfidence())
            .append(" → ").append(report.rootCauseCode());
        if (report.confidence() > 0) {
            sb.append(" (").append(String.format("%.0f%%", report.confidence() * 100)).append(")");
        }
        sb.append("\\n");
        sb.append("**Condition:** ").append(report.currentCondition()).append("\\n\\n");

        sb.append("**Impact:** ").append(truncate(report.businessImpact(), 200)).append("\\n\\n");

        if (!report.supportingEvidence().isEmpty()) {
            sb.append("**Key Evidence:**\\n");
            for (var e : report.supportingEvidence().stream().limit(5).toList()) {
                sb.append("- ").append(e.evidenceId()).append(": ")
                    .append(truncate(e.summary(), 100)).append("\\n");
            }
            sb.append("\\n");
        }

        if (!report.missingEvidence().isEmpty()) {
            sb.append("**Missing:** ").append(report.missingEvidence().size())
                .append(" items\\n\\n");
        }

        sb.append("**Next Steps:**\\n");
        for (String a : report.recommendedActions().stream().limit(3).toList()) {
            sb.append("- ").append(truncate(a, 120)).append("\\n");
        }
        sb.append("\\n");

        if (report.requiresHumanEscalation()) {
            sb.append("🚨 **Human escalation required**\\n");
        }

        sb.append("---\\n");
        sb.append("v").append(report.reportVersion())
            .append(" | `").append(report.contentHash()).append("`");

        return sb.toString();
    }

    private static String statusEmoji(HumanIncidentReport.IncidentStatus s) {
        return switch (s) {
            case ACTIVE -> "🔴";
            case RECOVERED -> "🟢";
            case UNKNOWN -> "🟡";
        };
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
