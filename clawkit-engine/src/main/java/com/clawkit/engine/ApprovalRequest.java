package com.clawkit.engine;

import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolRiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolCall;

/**
 * 审批请求（V2：直接携带 ToolMetadata，不再按工具名推断风险）。
 *
 * <p>旧 {@link #riskOf(String)} 和 {@link #riskDescriptionOf(String)} 已删除。
 * 风险等级从 metadata.riskLevel() 读取，副作用描述从 metadata.sideEffects() 生成。
 */
public record ApprovalRequest(
    String toolName,
    ToolRiskLevel riskLevel,
    String riskDescription,
    String parameters,
    String llmIntent,
    // ── V2 字段 ──────────────────────────────────────────────────
    ToolMetadata metadata,
    // ── P1-G 字段：授权必须绑定不可变 Action Contract ──────────
    String actionFingerprint
) {
    /** 从 ToolMetadata 和 ToolCall 构造（V2） */
    public static ApprovalRequest from(ToolMetadata metadata, ToolCall call, Message lastAssistantMsg) {
        return from(metadata, call, lastAssistantMsg, null);
    }

    public static ApprovalRequest from(ToolMetadata metadata, ToolCall call,
                                       Message lastAssistantMsg, String actionFingerprint) {
        String toolName = call.name();
        ToolRiskLevel risk = metadata.riskLevel();
        String riskDesc = describeRisk(metadata);
        String params = formatParams(call.arguments());
        String intent = extractIntent(lastAssistantMsg);
        return new ApprovalRequest(toolName, risk, riskDesc, params, intent, metadata,
            actionFingerprint);
    }

    /**
     * 旧工厂方法（保留兼容）。
     * @deprecated 使用 {@link #from(ToolMetadata, ToolCall, Message)}
     */
    @Deprecated
    public static ApprovalRequest from(ToolCall call, Message lastAssistantMsg) {
        var meta = com.clawkit.tools.ToolMetadata.conservative(call.name());
        return from(meta, call, lastAssistantMsg);
    }

    // ── 风险描述（从 metadata 生成，不再硬编码 toolName） ────────

    private static String describeRisk(ToolMetadata meta) {
        if (!meta.sideEffects().isEmpty()) {
            String effects = meta.sideEffects().stream()
                .map(se -> switch (se) {
                    case FILE_WRITE -> "文件写入";
                    case FILE_DELETE -> "文件删除";
                    case SHELL_EXEC -> "命令执行";
                    case NETWORK_OUT -> "网络访问";
                    case MESSAGE_SEND -> "消息发送";
                })
                .reduce((a, b) -> a + "、" + b).orElse("");
            return "副作用: " + effects;
        }
        if (meta.isDestructive()) return "可能具有破坏性";
        if (meta.isReadOnly()) return "只读操作";
        return "工具调用";
    }

    // ── 参数格式化（保留原有逻辑） ────────────────────────────────

    private static String formatParams(JsonNode args) {
        StringBuilder sb = new StringBuilder();
        if (args.has("file_path") || args.has("filePath")) {
            String path = args.has("file_path")
                ? args.get("file_path").asText()
                : args.get("filePath").asText();
            sb.append("path: \"").append(path).append("\"\n");
        }
        if (args.has("command")) {
            String cmd = args.get("command").asText();
            if (cmd.length() > 500) cmd = cmd.substring(0, 500) + "...";
            sb.append("command: ").append(cmd);
        } else if (args.has("content")) {
            String content = args.get("content").asText();
            if (content != null && !content.isEmpty()) {
                String preview = content.length() > 200
                    ? content.substring(0, 200).replace("\n", "\\n") + "..."
                    : content.replace("\n", "\\n");
                sb.append("content: \"").append(preview).append("\"")
                  .append(" (").append(content.length()).append(" 字符)");
            }
        }
        if (sb.isEmpty()) {
            String raw = args.toString();
            if (raw.length() > 300) raw = raw.substring(0, 300) + "...";
            sb.append(raw);
        }
        return sb.toString();
    }

    private static String extractIntent(Message lastAssistant) {
        if (lastAssistant == null) return null;
        String content = lastAssistant.content();
        if (content == null || content.isBlank()) {
            if (lastAssistant.toolCalls() != null && !lastAssistant.toolCalls().isEmpty()) {
                return "[调用工具: "
                    + lastAssistant.toolCalls().stream()
                        .map(tc -> tc.name())
                        .reduce((a, b) -> a + ", " + b).orElse("")
                    + "]";
            }
            return null;
        }
        return content.length() > 200
            ? content.substring(0, 200).replace("\n", " ") + "..."
            : content.replace("\n", " ");
    }
}
