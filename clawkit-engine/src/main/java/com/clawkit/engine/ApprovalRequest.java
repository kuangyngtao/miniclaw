package com.clawkit.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolCall;

public record ApprovalRequest(
    String toolName,
    RiskLevel riskLevel,
    String riskDescription,
    String parameters,
    String llmIntent
) {
    public static ApprovalRequest from(ToolCall call, Message lastAssistantMsg) {
        String toolName = call.name();
        RiskLevel risk = riskOf(toolName);
        String riskDesc = riskDescriptionOf(toolName);
        String params = formatParams(call.arguments());
        String intent = extractIntent(lastAssistantMsg);
        return new ApprovalRequest(toolName, risk, riskDesc, params, intent);
    }

    private static RiskLevel riskOf(String toolName) {
        return switch (toolName) {
            case "bash" -> RiskLevel.HIGH;
            case "write", "edit" -> RiskLevel.MEDIUM;
            default -> RiskLevel.LOW;
        };
    }

    private static String riskDescriptionOf(String toolName) {
        return switch (toolName) {
            case "bash" -> "执行系统命令，可能修改文件系统、网络或进程";
            case "write" -> "将写入或覆盖文件内容";
            case "edit" -> "将修改文件中的指定内容";
            case "todo_write" -> "更新任务列表";
            default -> "执行写操作";
        };
    }

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
