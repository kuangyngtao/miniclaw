package com.miniclaw.tools.schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Plan DAG 中的单个任务节点。
 * 身份字段在创建后不变；状态/结果字段在执行过程中由 PlanExecutor 更新。
 */
public class Task {

    private final String id;
    private final String description;
    private final TaskType taskType;
    private final List<String> dependencies;
    private final Map<String, String> inputSlots;
    private final String outputSlot;

    private volatile TaskStatus status;
    private volatile Instant startTime;
    private volatile Instant endTime;
    private volatile String result;
    private volatile String errorMessage;

    public Task(String id, String description, TaskType taskType,
                List<String> dependencies,
                Map<String, String> inputSlots,
                String outputSlot) {
        this.id = id;
        this.description = description;
        this.taskType = taskType;
        this.dependencies = dependencies;
        this.inputSlots = inputSlots;
        this.outputSlot = outputSlot;
        this.status = TaskStatus.PENDING;
    }

    // Immutable getters

    public String getId() { return id; }

    /** @deprecated use getId() for consistency */
    @Deprecated
    public String id() { return id; }

    public String getDescription() { return description; }
    public TaskType getTaskType() { return taskType; }

    public List<String> getDependencies() {
        return dependencies != null ? dependencies : List.of();
    }

    public Map<String, String> getInputSlots() {
        return inputSlots != null ? inputSlots : Map.of();
    }

    public String getOutputSlot() { return outputSlot; }

    // Mutable getters

    public TaskStatus getStatus() { return status; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public String getResult() { return result; }
    public String getErrorMessage() { return errorMessage; }

    // Mutable setters

    public void setStatus(TaskStatus status) { this.status = status; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    public void setResult(String result) { this.result = result; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
