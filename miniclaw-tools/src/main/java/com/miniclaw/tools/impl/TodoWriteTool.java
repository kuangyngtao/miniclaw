package com.miniclaw.tools.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.tools.Result;
import com.miniclaw.tools.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 自管理任务列表，会话级 ephemeral。
 * 每轮 Agent 可更新 todos 列表，工具返回格式化的当前状态。
 */
public class TodoWriteTool implements Tool {

    private static final Set<String> VALID_STATUSES = Set.of("pending", "in_progress", "completed");

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
                  "status": {"type": "string", "enum": ["pending", "in_progress", "completed"]},
                  "activeForm": {"type": "string", "description": "进行中时的显示文本"}
                },
                "required": ["content", "status", "activeForm"]
              }
            }
          },
          "required": ["todos"]
        }""";

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
            + "status: pending（待做）, in_progress（进行中，一次只能一个）, completed（已完成）。";
    }

    @Override
    public String inputSchema() {
        return SCHEMA;
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
                    "非法 status: '" + status + "'，允许值: pending, in_progress, completed"));
            }

            entries.add(new TodoEntry(contentNode.asText(), status, activeFormNode.asText()));
        }

        todos.set(entries);
        return new Result.Ok<>(formatTodos(entries));
    }

    private String formatTodos(List<TodoEntry> entries) {
        if (entries.isEmpty()) {
            return "任务列表为空。";
        }
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

    private record TodoEntry(String content, String status, String activeForm) {}
}
