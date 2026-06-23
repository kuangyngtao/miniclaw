package com.miniclaw.tools.schema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 完整的执行计划 — DAG 任务集合 + 拓扑排序 + 状态。
 */
public class ExecutionPlan {

    private String id;
    private String goal;
    private final Map<String, Task> tasks;
    private List<String> executionOrder;
    private PlanStatus status;
    private String summary;

    public ExecutionPlan(String goal) {
        this.goal = goal;
        this.tasks = new ConcurrentHashMap<>();
        this.status = PlanStatus.CREATED;
    }

    public void addTask(Task task) {
        tasks.put(task.getId(), task);
    }

    public Task getTask(String id) {
        return tasks.get(id);
    }

    public Map<String, Task> getTasks() {
        return tasks;
    }

    public int taskCount() {
        return tasks.size();
    }

    // Getters and setters

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }

    public List<String> getExecutionOrder() { return executionOrder; }
    public void setExecutionOrder(List<String> executionOrder) { this.executionOrder = executionOrder; }

    public PlanStatus getStatus() { return status; }
    public void setStatus(PlanStatus status) { this.status = status; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
