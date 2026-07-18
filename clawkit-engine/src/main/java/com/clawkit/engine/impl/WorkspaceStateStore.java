package com.clawkit.engine.impl;

import com.clawkit.tools.schema.Message;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the engine's restart state under {@code .clawkit}. */
final class WorkspaceStateStore {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceStateStore.class);
    private final Path root;
    private final Path stateDir;

    WorkspaceStateStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.stateDir = this.root.resolve(".clawkit");
    }

    List<Message> recallContext() {
        StringBuilder state = new StringBuilder();
        appendIfPresent(state, "todo.md", "当前任务进度");
        appendIfPresent(state, "plan.md", "当前计划");
        if (state.isEmpty()) return List.of();
        return List.of(Message.system(
            "[Workspace State] 以下是上次中断前的工作进度，请据此续接：\n" + state));
    }

    void writeTodo(JsonNode args) {
        if (args == null) return;
        JsonNode todos = args.get("todos");
        if (todos == null || !todos.isArray()) return;
        StringBuilder md = new StringBuilder("# TODO\n\n");
        for (JsonNode item : todos) {
            String status = item.path("status").asText("pending");
            String content = item.path("content").asText("");
            String active = item.path("activeForm").asText("");
            String marker = switch (status) {
                case "completed" -> "x";
                case "in_progress" -> "~";
                default -> " ";
            };
            md.append("- [").append(marker).append("] ")
                .append("in_progress".equals(status) && !active.isBlank() ? active : content)
                .append('\n');
        }
        write("todo.md", md.toString());
    }

    void writePlan(String content) {
        if (content != null && !content.isBlank()) write("plan.md", content);
    }

    String read(String relativePath) {
        try {
            Path file = safeResolve(relativePath);
            if (!Files.exists(file)) return null;
            String content = Files.readString(file);
            return content.isBlank() ? null : content;
        } catch (Exception e) {
            log.debug("[Workspace] read {} failed: {}", relativePath, e.getMessage());
            return null;
        }
    }

    private void appendIfPresent(StringBuilder state, String name, String heading) {
        String content = read(".clawkit/" + name);
        if (content != null) {
            state.append("\n## ").append(heading).append(" (from .clawkit/")
                .append(name).append(")\n\n").append(content);
        }
    }

    private void write(String name, String content) {
        try {
            Files.createDirectories(stateDir);
            Files.writeString(stateDir.resolve(name), content);
            log.info("[Workspace] wrote .clawkit/{}", name);
        } catch (Exception e) {
            log.warn("[Workspace] write {} failed: {}", name, e.getMessage());
        }
    }

    private Path safeResolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("path escapes workspace");
        return resolved;
    }
}
