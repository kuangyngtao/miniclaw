package com.clawkit.tools;

import com.clawkit.tools.schema.ToolCall;
import com.clawkit.tools.schema.ToolDefinition;
import com.clawkit.tools.schema.ToolResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry 的默认实现 — 线程安全，支持运行时注册/注销。
 */
public class ToolRegistry implements Registry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ToolMount> mounts = new ConcurrentHashMap<>();
    private final List<SafetyInterceptor> interceptors = new ArrayList<>();

    @Override
    public void register(Tool tool) {
        if (tools.containsKey(tool.name())) {
            log.warn("工具 '{}' 已被注册，将被覆盖。", tool.name());
        }
        tools.put(tool.name(), tool);
        log.info("成功挂载工具: {}", tool.name());
    }

    /** 批量注册 */
    public void registerAll(Collection<Tool> tools) {
        tools.forEach(this::register);
    }

    @Override
    public Optional<Tool> lookup(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 查询工具是否为只读工具 */
    public boolean isReadOnly(String toolName) {
        Tool tool = tools.get(toolName);
        return tool != null && tool.isReadOnly();
    }

    /** 注册安全拦截器 */
    public void addInterceptor(SafetyInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    @Override
    public List<ToolDefinition> getAvailableTools() {
        return tools.values().stream()
            .sorted(java.util.Comparator.comparing(Tool::name))
            .map(t -> new ToolDefinition(t.name(), t.description(), t.inputSchema()))
            .toList();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        for (SafetyInterceptor i : interceptors) {
            String blockReason = i.check(call);
            if (blockReason != null) {
                log.warn("[Registry] 工具调用被拦截: {}", blockReason);
                return ToolResult.error(call.id(), blockReason);
            }
        }
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.error(call.id(), "tool not found: " + call.name());
        }
        try {
            Result<String> result = tool.execute(
                call.arguments() != null ? call.arguments().toString() : "{}");
            return switch (result) {
                case Result.Ok<String> ok -> ToolResult.success(call.id(), ok.data());
                case Result.Err<String> err ->
                    ToolResult.error(call.id(),
                        "[" + err.error().errorCode() + "] " + err.error().message());
            };
        } catch (Exception e) {
            return ToolResult.error(call.id(), call.name() + " 执行异常: " + e.getMessage());
        }
    }

    /** 查询工具元数据。未知工具返回保守默认值。 */
    public ToolMetadata metadata(String toolName) {
        Tool tool = tools.get(toolName);
        return tool != null ? tool.metadata() : ToolMetadata.conservative(toolName);
    }

    /** 结构化执行入口（新接口），支持耗时和 outputBytes 统计。 */
    public ToolExecutionResult execute(ToolExecutionRequest req) {
        for (SafetyInterceptor i : interceptors) {
            String blockReason = i.check(new com.clawkit.tools.schema.ToolCall(
                req.toolCallId(), req.toolName(), req.arguments()));
            if (blockReason != null) {
                return ToolExecutionResult.error(
                    req.toolCallId(), req.toolName(), "BLOCKED", blockReason, 0, metadata(req.toolName()));
            }
        }
        Tool tool = tools.get(req.toolName());
        if (tool == null) {
            return ToolExecutionResult.error(
                req.toolCallId(), req.toolName(), "NOT_FOUND",
                "tool not found: " + req.toolName(), 0, ToolMetadata.conservative(req.toolName()));
        }
        return tool.execute(req);
    }

    /** 工具数量 */
    public int count() {
        return tools.size();
    }

    // ── Namespace mount (REMOTE-0 §9.1) ───────────────────────────────

    /**
     * Atomically mount a collection of tools under a named owner.
     *
     * <p>All tools are checked for name conflicts first; if any conflict,
     * zero tools are mounted and a {@link ToolNamespaceCollisionException}
     * is thrown. On success, a {@link ToolMount} handle is returned —
     * closing it removes only the tools that still belong to this mount.
     *
     * <p>This is the safe replacement for {@link #register(Tool)} when
     * mounting remote/dynamic tools. Built-in tools registered via
     * {@code register()} are not tracked by mounts.
     *
     * @param ownerId unique owner identifier (e.g., "remote:test-server")
     * @param toolsToMount the tools to mount
     * @return a closeable mount handle
     * @throws ToolNamespaceCollisionException if any tool name conflicts
     */
    public ToolMount mount(String ownerId, Collection<Tool> toolsToMount) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId must not be blank");
        }
        if (toolsToMount == null || toolsToMount.isEmpty()) {
            throw new IllegalArgumentException("toolsToMount must not be empty");
        }

        // Check for duplicates within the batch (no lock needed — input is local)
        List<String> names = toolsToMount.stream().map(Tool::name).toList();
        Set<String> uniqueNames = Set.copyOf(names);
        if (uniqueNames.size() != names.size()) {
            throw new ToolNamespaceCollisionException(ownerId,
                "duplicate tool names within mount batch");
        }

        // Atomic check-then-mount under a single lock to prevent races
        synchronized (this) {
            // Reject duplicate ownerId
            if (mounts.containsKey(ownerId)) {
                throw new ToolNamespaceCollisionException(ownerId,
                    "mount owner already active: " + ownerId + " — close previous mount first");
            }

            // Check for conflicts with existing tools
            List<String> conflicts = new ArrayList<>();
            for (String name : uniqueNames) {
                if (tools.containsKey(name)) {
                    conflicts.add(name);
                }
            }
            if (!conflicts.isEmpty()) {
                throw new ToolNamespaceCollisionException(ownerId,
                    "tool name collision: " + String.join(", ", conflicts));
            }

            // Atomic mount: all-or-nothing under the lock
            List<String> mountedNames = new ArrayList<>();
            for (Tool tool : toolsToMount) {
                tools.put(tool.name(), tool);
                mountedNames.add(tool.name());
            }

            var mount = new OwnedToolMount(ownerId, List.copyOf(mountedNames),
                List.copyOf(toolsToMount), this);
            mounts.put(ownerId, mount);
            log.info("[Registry] mounted {} tools for owner '{}'", mountedNames.size(), ownerId);
            return mount;
        }
    }

    /** Check if an owner has an active mount. */
    public boolean hasMount(String ownerId) {
        return mounts.containsKey(ownerId);
    }

    /** Get the active mount for an owner, if any. */
    public Optional<ToolMount> getMount(String ownerId) {
        return Optional.ofNullable(mounts.get(ownerId));
    }

    /** Return the tool instances belonging to a mount (debug/testing). */
    List<Tool> toolsForOwner(String ownerId) {
        ToolMount mount = mounts.get(ownerId);
        if (mount == null) return List.of();
        return mount.toolNames().stream()
            .map(tools::get)
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    // ── Inner: OwnedToolMount ─────────────────────────────────────────

    private static class OwnedToolMount implements ToolMount {
        private final String ownerId;
        private final List<String> toolNames;
        private final List<Tool> ownedInstances;
        private final ToolRegistry registry;
        private volatile boolean closed;

        OwnedToolMount(String ownerId, List<String> toolNames, List<Tool> ownedInstances,
                       ToolRegistry registry) {
            this.ownerId = ownerId;
            this.toolNames = toolNames;
            this.ownedInstances = ownedInstances;
            this.registry = registry;
        }

        @Override
        public String ownerId() { return ownerId; }

        @Override
        public List<String> toolNames() { return toolNames; }

        @Override
        public void close() {
            if (closed) return;
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            // Conditional remove: only delete if the current tool IS our owned instance.
            // This prevents deleting a later-registered tool with the same name.
            for (int i = 0; i < ownedInstances.size(); i++) {
                String name = toolNames.get(i);
                Tool owned = ownedInstances.get(i);
                registry.tools.remove(name, owned);
            }
            registry.mounts.remove(ownerId);
            log.info("[Registry] unmounted {} tools for owner '{}'", toolNames.size(), ownerId);
        }
    }

    /** Thrown when a tool mount would collide with existing tools. */
    public static class ToolNamespaceCollisionException extends RuntimeException {
        private final String ownerId;
        public ToolNamespaceCollisionException(String ownerId, String message) {
            super(message);
            this.ownerId = ownerId;
        }
        public String ownerId() { return ownerId; }
    }
}
