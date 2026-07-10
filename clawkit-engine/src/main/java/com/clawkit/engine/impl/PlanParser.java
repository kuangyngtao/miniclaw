package com.clawkit.engine.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawkit.tools.Result;
import com.clawkit.tools.schema.ExecutionPlan;
import com.clawkit.tools.schema.Task;
import com.clawkit.tools.schema.TaskType;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 解析 LLM 输出的 JSON 字符串，构建带执行层级的 ExecutionPlan。
 * 不用拓扑排序——直接用递归深度计算并行层级，依赖深度相同的任务可并行。
 */
public class PlanParser {

    private static final Logger log = LoggerFactory.getLogger(PlanParser.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public Result<ExecutionPlan> parse(String planJson) {
        try {
            String cleaned = stripMarkdownFences(planJson);
            JsonNode root = mapper.readTree(cleaned);

            String goal = root.has("goal") ? root.get("goal").asText() : "";
            if (goal.isBlank()) {
                return new Result.Err<>(new Result.ErrorInfo("P-001", "缺少 goal"));
            }

            JsonNode tasksNode = root.get("tasks");
            if (tasksNode == null || !tasksNode.isObject() || tasksNode.isEmpty()) {
                return new Result.Err<>(new Result.ErrorInfo("P-001", "缺少 tasks 或为空"));
            }

            ExecutionPlan plan = new ExecutionPlan(goal);
            Set<String> taskIds = new LinkedHashSet<>();

            Iterator<Map.Entry<String, JsonNode>> fields = tasksNode.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode taskNode = entry.getValue();

                String id = taskNode.has("id") ? taskNode.get("id").asText() : entry.getKey();
                if (!taskIds.add(id)) {
                    return new Result.Err<>(new Result.ErrorInfo("P-002", "重复的任务ID: " + id));
                }

                String desc = taskNode.has("description") ? taskNode.get("description").asText() : "";
                if (desc.isBlank()) {
                    return new Result.Err<>(new Result.ErrorInfo("P-002", "任务 " + id + " 缺少 description"));
                }

                TaskType type = parseTaskType(taskNode, id);
                List<String> deps = parseStringList(taskNode, "dependencies");
                Map<String, String> slots = parseInputSlots(taskNode);
                String outputSlot = taskNode.has("outputSlot") && !taskNode.get("outputSlot").isNull()
                    ? taskNode.get("outputSlot").asText() : null;

                plan.addTask(new Task(id, desc, type, deps, slots, outputSlot));
            }

            String validationError = validateDag(plan);
            if (validationError != null) {
                return new Result.Err<>(new Result.ErrorInfo("P-003", validationError));
            }

            List<List<String>> levels = computeLevels(plan.getTasks());
            List<String> order = new ArrayList<>();
            for (List<String> level : levels) {
                order.addAll(level);
            }
            plan.setExecutionOrder(order);
            plan.setId(UUID.randomUUID().toString().substring(0, 8));

            log.info("[PlanParser] 解析成功: {} tasks, {} levels",
                plan.taskCount(), computeLevels(plan.getTasks()).size());
            return new Result.Ok<>(plan);

        } catch (Exception e) {
            log.warn("[PlanParser] 解析失败: {}", e.getMessage());
            return new Result.Err<>(new Result.ErrorInfo("P-001", "JSON 解析失败: " + e.getMessage()));
        }
    }

    // === DAG 校验 ===

    private String validateDag(ExecutionPlan plan) {
        Map<String, Task> tasks = plan.getTasks();
        Set<String> allIds = tasks.keySet();

        for (Task task : tasks.values()) {
            for (String dep : task.getDependencies()) {
                if (!allIds.contains(dep)) {
                    return "任务 " + task.getId() + " 依赖的任务 " + dep + " 不存在";
                }
            }
            for (var slotEntry : task.getInputSlots().entrySet()) {
                String sourceTaskId = slotEntry.getValue();
                if (!allIds.contains(sourceTaskId)) {
                    return "任务 " + task.getId() + " 的 slot " + slotEntry.getKey()
                        + " 引用的任务 " + sourceTaskId + " 不存在";
                }
            }
        }

        // 环检测：DFS 三色标记
        Map<String, Color> colors = new HashMap<>();
        for (String id : allIds) {
            colors.put(id, Color.WHITE);
        }
        for (String id : allIds) {
            if (colors.get(id) == Color.WHITE) {
                String cycleNode = detectCycle(id, tasks, colors);
                if (cycleNode != null) {
                    return "存在循环依赖，涉及任务: " + cycleNode;
                }
            }
        }

        return null;
    }

    private String detectCycle(String nodeId, Map<String, Task> tasks,
                                Map<String, Color> colors) {
        colors.put(nodeId, Color.GRAY);

        Task task = tasks.get(nodeId);
        if (task != null) {
            for (String dep : task.getDependencies()) {
                Color depColor = colors.get(dep);
                if (depColor == Color.GRAY) {
                    return dep;
                }
                if (depColor == Color.WHITE) {
                    String found = detectCycle(dep, tasks, colors);
                    if (found != null) return found;
                }
            }
        }

        colors.put(nodeId, Color.BLACK);
        return null;
    }

    // === 层级计算（递归深度 → 拓扑顺序 + 并行分组） ===

    /**
     * 根据依赖深度分组。无依赖的任务为 level 0，依赖深度为 N 的任务为 level N+1。
     * 同一 level 内的任务没有相互依赖，可以并行执行。
     */
    public static List<List<String>> computeLevels(Map<String, Task> tasks) {
        Map<String, Integer> depth = new HashMap<>();
        Map<Integer, List<String>> levelMap = new TreeMap<>();

        for (String id : tasks.keySet()) {
            int d = computeDepth(id, tasks, depth, new HashSet<>());
            levelMap.computeIfAbsent(d, k -> new ArrayList<>()).add(id);
        }

        List<List<String>> levels = new ArrayList<>();
        for (int i = 0; levelMap.containsKey(i); i++) {
            levels.add(levelMap.get(i));
        }
        return levels;
    }

    private static int computeDepth(String id, Map<String, Task> tasks,
                                     Map<String, Integer> cache, Set<String> visited) {
        Integer cached = cache.get(id);
        if (cached != null) return cached;

        if (!visited.add(id)) return 0; // 环已在 validateDag 中拦截

        Task task = tasks.get(id);
        if (task == null || task.getDependencies().isEmpty()) {
            cache.put(id, 0);
            return 0;
        }

        int maxDepDepth = 0;
        for (String dep : task.getDependencies()) {
            int depDepth = computeDepth(dep, tasks, cache, visited);
            if (depDepth >= maxDepDepth) {
                maxDepDepth = depDepth + 1;
            }
        }
        cache.put(id, maxDepDepth);
        return maxDepDepth;
    }

    // === JSON 辅助 ===

    private String stripMarkdownFences(String json) {
        String text = json.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            if (start < 0) start = 3;
            else start = start + 1;
            int end = text.lastIndexOf("```");
            if (end > start) {
                return text.substring(start, end).trim();
            }
            return text.substring(start).trim();
        }
        return text;
    }

    private TaskType parseTaskType(JsonNode node, String id) {
        if (!node.has("type")) return TaskType.MODIFY;
        String typeStr = node.get("type").asText().toUpperCase();
        try {
            return TaskType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            log.warn("[PlanParser] 任务 {} 的未知类型: {}, 默认 MODIFY", id, typeStr);
            return TaskType.MODIFY;
        }
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

    private Map<String, String> parseInputSlots(JsonNode node) {
        JsonNode slots = node.get("inputSlots");
        if (slots == null || !slots.isObject()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = slots.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            result.put(entry.getKey(), entry.getValue().asText());
        }
        return result;
    }

    private enum Color { WHITE, GRAY, BLACK }
}
