package com.clawkit.tools.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawkit.tools.Result;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolBehavior;
import com.clawkit.tools.ToolExecutionPolicy;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolMetadataProvenance;
import com.clawkit.tools.ToolRiskLevel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 自管理任务列表，会话级 ephemeral。
 * 每轮 Agent 可更新 todos 列表，工具返回格式化的当前状态。
 * 支持 Plan-and-Execute 模式：可选 taskType + dependencies 字段，emoji 可视化。
 */
public class TodoWriteTool implements Tool {

    private static final Set<String> VALID_STATUSES =
        Set.of("pending", "in_progress", "completed", "failed", "skipped");

    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "todos": {
              "type": "array",
              "description": "任务列表",
              "items": {
                "type": "object",
                "properties": {
                  "content": {"type": "string", "description": "任务描述"},
                  "status": {"type": "string", "enum": ["pending", "in_progress", "completed", "failed", "skipped"]},
                  "activeForm": {"type": "string", "description": "进行中时的显示文本"},
                  "dependencies": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "上游依赖的任务ID（可选）"
                  },
                  "taskType": {
                    "type": "string",
                    "enum": ["EXPLORE", "MODIFY", "VERIFY"],
                    "description": "任务类型（可选）"
                  }
                },
                "required": ["content", "status", "activeForm"]
              }
            }
          },
          "required": ["todos"]
        }""";

    private static final Logger log = LoggerFactory.getLogger(TodoWriteTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AtomicReference<List<TodoEntry>> todos = new AtomicReference<>(List.of());

    @Override
    public String name() {
        return "todo_write";
    }

    @Override
    public String description() {
        return "管理当前会话的任务列表。用于追踪多步骤任务的完成进度。"
            + "每轮可更新列表中任意任务的 status。"
            + "status: pending（待做）, in_progress（进行中，一次只能一个）, completed（已完成）, failed（失败）, skipped（跳过）。";
    }

    @Override
    public String inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
            name(), description(), null, null,
            new ToolBehavior(false, ToolRiskLevel.MEDIUM, false, false, false, true, Set.of()),
            new ToolExecutionPolicy(Duration.ofSeconds(5), 2000,
                ToolExecutionPolicy.OutputTruncation.HEAD, ToolExecutionPolicy.ToolConcurrency.SERIAL),
            ToolMetadataProvenance.builtin(name())
        );
    }

    @Override
    public Result<String> execute(String arguments) {
        JsonNode argsNode;
        try {
            argsNode = mapper.readTree(arguments);
        } catch (JsonProcessingException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-002", "参数 JSON 解析失败: " + e.getMessage()));
        }

        JsonNode todosNode = argsNode.get("todos");
        if (todosNode == null || !todosNode.isArray()) {
            return new Result.Err<>(new Result.ErrorInfo("T-002", "缺少必需参数 'todos' (数组)"));
        }

        List<TodoEntry> entries = new ArrayList<>();
        for (JsonNode item : todosNode) {
            JsonNode contentNode = item.get("content");
            JsonNode statusNode = item.get("status");
            JsonNode activeFormNode = item.get("activeForm");

            if (contentNode == null || contentNode.asText().isEmpty()) {
                return new Result.Err<>(new Result.ErrorInfo("T-002", "todo 项缺少 'content'"));
            }
            if (activeFormNode == null || activeFormNode.asText().isEmpty()) {
                return new Result.Err<>(new Result.ErrorInfo("T-002", "todo 项缺少 'activeForm'"));
            }
            String status = statusNode != null ? statusNode.asText() : "pending";
            if (!VALID_STATUSES.contains(status)) {
                return new Result.Err<>(new Result.ErrorInfo("T-002",
                    "非法 status: '" + status + "'，允许值: pending, in_progress, completed, failed, skipped"));
            }

            List<String> deps = parseStringList(item, "dependencies");
            String taskType = item.has("taskType") ? item.get("taskType").asText() : null;

            entries.add(new TodoEntry(contentNode.asText(), status, activeFormNode.asText(), deps, taskType));
        }

        todos.set(entries);
        log.info("[TodoWrite] {} items: {}",
            entries.size(),
            entries.stream().map(e -> e.status() + ":" + e.content()).toList());
        return new Result.Ok<>(formatTodos(entries));
    }

    private String formatTodos(List<TodoEntry> entries) {
        if (entries.isEmpty()) {
            return "任务列表为空。";
        }

        // 检查是否有任何条目包含 Plan 增强字段
        boolean hasPlanFields = entries.stream()
            .anyMatch(e -> e.taskType() != null || !e.dependencies().isEmpty());

        if (hasPlanFields) {
            return formatPlanTodos(entries);
        }
        return formatSimpleTodos(entries);
    }

    /** 旧格式：简单扁平列表，向后兼容 */
    private String formatSimpleTodos(List<TodoEntry> entries) {
        StringBuilder sb = new StringBuilder();
        int total = entries.size();
        for (int i = 0; i < total; i++) {
            TodoEntry e = entries.get(i);
            sb.append("[").append(i + 1).append("/").append(total).append("] ");
            if ("completed".equals(e.status())) {
                sb.append("[completed] ").append(e.content());
            } else if ("in_progress".equals(e.status())) {
                sb.append(e.activeForm());
            } else {
                sb.append("[pending] ").append(e.content());
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** 增强格式：emoji + 任务类型标签 + 依赖关系 */
    private String formatPlanTodos(List<TodoEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append(executionPlanHeader(entries));
        sb.append("\n");

        int total = entries.size();
        for (int i = 0; i < total; i++) {
            TodoEntry e = entries.get(i);
            String emoji = statusEmoji(e.status());
            String typeTag = e.taskType() != null
                ? "  [" + e.taskType().toLowerCase() + "]" : "";

            sb.append(String.format("  %s [%d/%d] %s%s%n",
                emoji, i + 1, total, e.content(), typeTag));

            if (!e.dependencies().isEmpty()) {
                sb.append("       depends on: ").append(String.join(", ", e.dependencies())).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    /** 生成 Plan 模式标题栏 */
    private String executionPlanHeader(List<TodoEntry> entries) {
        long pending = entries.stream().filter(e -> "pending".equals(e.status())).count();
        long inProgress = entries.stream().filter(e -> "in_progress".equals(e.status())).count();
        long completed = entries.stream().filter(e -> "completed".equals(e.status())).count();
        long failed = entries.stream().filter(e -> "failed".equals(e.status())).count();

        StringBuilder sb = new StringBuilder();
        sb.append("  Execution Plan  ");
        if (completed > 0) sb.append("  ").append(completed).append(" done");
        if (inProgress > 0) sb.append("    ").append(inProgress).append(" active");
        if (pending > 0) sb.append("    ").append(pending).append(" pending");
        if (failed > 0) sb.append("  ").append(failed).append(" failed");
        return sb.toString();
    }

    private static String statusEmoji(String status) {
        return switch (status) {
            case "pending"     -> "⏳"; // ⏳
            case "in_progress" -> "🔄"; // 🔄
            case "completed"   -> "✅"; // ✅
            case "failed"      -> "❌"; // ❌
            case "skipped"     -> "⤵️"; // ⤵️
            default            -> "  ";
        };
    }

    private List<String> parseStringList(JsonNode node, String field) {
        JsonNode arr = node.get(field);
        if (arr == null || !arr.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : arr) {
            result.add(item.asText());
        }
        return result;
    }

    private record TodoEntry(
        String content,
        String status,
        String activeForm,
        List<String> dependencies,
        String taskType
    ) {}
}
