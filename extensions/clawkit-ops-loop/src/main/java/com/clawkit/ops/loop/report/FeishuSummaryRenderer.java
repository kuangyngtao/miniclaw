package com.clawkit.ops.loop.report;

/**
 * Renders a compact Chinese Feishu summary from {@link HumanIncidentReport}.
 *
 * <p>R4: All output in Chinese for operations team readability.
 * NEVER contains: SSH host/user/key, DB URL/password, appId/appSecret,
 * chat_id, message_id, control tokens, Case manifest, Ground Truth,
 * full logs, order IDs, account IDs, local file paths.
 */
public final class FeishuSummaryRenderer {

    private FeishuSummaryRenderer() {}

    public static String render(HumanIncidentReport report) {
        StringBuilder sb = new StringBuilder();

        sb.append("🔍 **事故报告**\\n\\n");
        sb.append("> ⚠️ ").append(report.dataLabel()).append("\\n\\n");

        sb.append("**事故ID：** `").append(report.incidentId()).append("`\\n");
        sb.append("**场景：** ").append(report.profileName()).append("\\n");
        sb.append("**状态：** ").append(statusLabel(report.status())).append("\\n");
        sb.append("**诊断：** ").append(diagLabel(report.diagnosisConfidence()))
            .append(" → ").append(report.rootCauseCode());
        if (report.confidence() > 0) {
            sb.append("（置信度 ").append(String.format("%.0f%%", report.confidence() * 100)).append("）");
        }
        sb.append("\\n");
        sb.append("**当前状况：** ").append(report.currentCondition()).append("\\n\\n");

        sb.append("**影响：** ").append(truncate(report.businessImpact(), 200)).append("\\n\\n");

        if (!report.supportingEvidence().isEmpty()) {
            sb.append("**关键证据：**\\n");
            for (var e : report.supportingEvidence().stream().limit(5).toList()) {
                sb.append("- ").append(e.evidenceId()).append("（").append(e.type())
                    .append("）：").append(truncate(e.summary(), 100)).append("\\n");
            }
            sb.append("\\n");
        }

        if (!report.contradictingEvidence().isEmpty()) {
            sb.append("**反证：**\\n");
            for (var e : report.contradictingEvidence().stream().limit(3).toList()) {
                sb.append("- ").append(e.evidenceId()).append("：").append(truncate(e.summary(), 100)).append("\\n");
            }
            sb.append("\\n");
        }

        if (!report.missingEvidence().isEmpty()) {
            sb.append("**缺失信息：** ").append(report.missingEvidence().size()).append(" 项\\n\\n");
        }

        if (!report.failedOrStaleEvidence().isEmpty()) {
            sb.append("**失败/过期证据：** ").append(report.failedOrStaleEvidence().size()).append(" 项\\n\\n");
        }

        sb.append("**建议操作：**\\n");
        for (String a : report.recommendedActions().stream().limit(3).toList()) {
            sb.append("- ").append(truncate(actionLabel(a), 120)).append("\\n");
        }
        sb.append("\\n");

        if (report.requiresHumanEscalation()) {
            sb.append("🚨 **需要人工介入**\\n");
            if (report.escalationReason() != null) {
                sb.append(report.escalationReason()).append("\\n");
            }
            sb.append("\\n");
        }

        sb.append("---\\n");
        sb.append("v").append(report.reportVersion())
            .append(" | `").append(report.contentHash()).append("`");

        return sb.toString();
    }

    private static String statusLabel(HumanIncidentReport.IncidentStatus s) {
        return switch (s) {
            case ACTIVE -> "🔴 活跃故障";
            case RECOVERED -> "🟢 已恢复";
            case UNKNOWN -> "🟡 状态未知";
        };
    }

    private static String diagLabel(HumanIncidentReport.DiagnosisConfidence d) {
        return switch (d) {
            case CONFIRMED -> "已确认";
            case PROBABLE -> "很可能";
            case INCONCLUSIVE -> "无法确定";
        };
    }

    private static String actionLabel(String code) {
        if (code == null) return "升级人工处理";
        return switch (code) {
            case "ESCALATE", "ESCALATE_TO_HUMAN" -> "升级人工处理";
            case "RESTART_SERVICE" -> "重启服务";
            case "COLLECT_MORE_EVIDENCE" -> "收集更多证据";
            default -> code;
        };
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
