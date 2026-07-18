package com.clawkit.cli;

import com.clawkit.context.SkillLoader;
import com.clawkit.engine.SessionMeta;
import com.clawkit.engine.SessionService;
import com.clawkit.engine.SessionStoreException;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.memory.MemoryEntry;
import com.clawkit.memory.MemoryType;
import com.clawkit.memory.impl.DiskMemoryService;
import com.clawkit.observability.RunReader;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.mcp.McpManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.jline.reader.LineReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Business handlers for parameterized slash commands. */
final class CliCommandHandlers {
    private static final Logger log = LoggerFactory.getLogger(CliCommandHandlers.class);
    private final AgentEngine engine;
    private final SessionService sessions;
    private final SkillLoader skills;
    private final McpManager mcp;
    private final ToolRegistry registry;
    private final DiskMemoryService memory;
    private final RunReader runs;
    private final LineReader reader;

    CliCommandHandlers(AgentEngine engine, SessionService sessions, SkillLoader skills,
                       McpManager mcp, ToolRegistry registry, DiskMemoryService memory,
                       RunReader runs, LineReader reader) {
        this.engine = engine;
        this.sessions = sessions;
        this.skills = skills;
        this.mcp = mcp;
        this.registry = registry;
        this.memory = memory;
        this.runs = runs;
        this.reader = reader;
    }

    boolean handle(SlashCommandRouter.Command command) {
        return switch (command.name()) {
            case "remember" -> { remember(command.arguments()); yield true; }
            case "memory" -> { memory(command.arguments()); yield true; }
            case "session" -> { session(command.arguments()); yield true; }
            case "skill" -> { skill(command.arguments()); yield true; }
            case "mcp" -> { mcp(command.arguments()); yield true; }
            case "runs" -> { runs(); yield true; }
            case "metrics" -> { metrics(command.arguments()); yield true; }
            case "trace" -> { trace(command.arguments()); yield true; }
            default -> false;
        };
    }

    private void remember(String content) {
        if (content.isBlank()) {
            content = reader.readLine(gray("  What should I remember? "));
            if (content == null || content.isBlank()) { println("  Cancelled."); return; }
        }
        String name = "", description = "";
        MemoryType type = MemoryType.USER;
        try {
            String json = engine.extractMemoryMetadata(content);
            var node = new ObjectMapper().readTree(json);
            name = node.path("name").asText("");
            description = node.path("description").asText("");
            type = parseType(node.path("type").asText("user"), MemoryType.USER);
        } catch (Exception e) {
            log.warn("Memory metadata extraction failed: {}", e.getMessage());
        }
        if (name.isBlank()) name = sanitize(content.length() > 30 ? content.substring(0, 30) : content);
        if (description.isBlank()) description = abbreviate(content, 100);
        MemoryEntry entry = new MemoryEntry(name, description, type, Instant.now(), content);
        var result = engine.rememberMemory(entry);
        println(result.saved() > 0
            ? "  saved " + entry.filename() + " [" + type.name().toLowerCase() + "] " + description
            : "  unchanged " + entry.filename() + " (duplicate)");
    }

    private void memory(String args) {
        if (args.isBlank() || args.equals("list")) {
            var entries = memory.listIndex();
            if (entries.isEmpty()) { println("  No memory entries."); return; }
            System.out.println();
            entries.forEach(e -> println("  - [" + e.name() + "](" + e.filename() + ") — " + e.description()));
            System.out.println();
            return;
        }
        if (args.equals("regen")) {
            memory.regenerateIndex();
            engine.setMemoryIndex(memory.loadIndex());
            println("  Index regenerated from files.");
            return;
        }
        if (args.startsWith("add ")) {
            String[] parts = args.substring(4).trim().split("\\s+", 3);
            if (parts.length < 3) { println("  Usage: /memory add <type> <name> <content>"); return; }
            MemoryType type = parseType(parts[0], null);
            if (type == null) { println("  Invalid type: " + parts[0]); return; }
            MemoryEntry entry = new MemoryEntry(sanitize(parts[1]), abbreviate(parts[2], 100),
                type, Instant.now(), parts[2]);
            var result = engine.rememberMemory(entry);
            println(result.saved() > 0
                ? "  saved " + entry.filename() + " [" + type.name().toLowerCase() + "]"
                : "  unchanged " + entry.filename() + " (duplicate)");
            return;
        }
        println("  Usage: /memory list | /memory add <type> <name> <content> | /memory regen");
    }

    private void session(String args) {
        if (args.isBlank()) { sessionUsage(); return; }
        try {
            if (args.startsWith("save")) {
                String name = args.substring(4).trim();
                if (name.isEmpty()) name = "session-" + Instant.now().toString().replace(":", "-").substring(0, 19);
                println("  " + engine.saveSession(name));
            } else if (args.startsWith("load ")) {
                String id = args.substring(5).trim(); engine.loadSession(id); println("  Session loaded: " + id);
            } else if (args.equals("list")) {
                printSessions(engine.listSessions(), false);
            } else if (args.startsWith("delete ")) {
                String id = args.substring(7).trim(); engine.deleteSession(id); println("  Session deleted: " + id);
            } else if (args.startsWith("search ")) {
                printSessions(sessions.search(args.substring(7).trim()), true);
            } else if (args.equals("stats")) {
                printStats();
            } else if (args.startsWith("prune ")) {
                int days = Integer.parseInt(args.substring(6).trim());
                if (days <= 0) throw new NumberFormatException();
                println("  Pruned " + sessions.prune(days) + " session(s) older than " + days + " days.");
            } else if (args.equals("new")) {
                println("  " + engine.newSession());
            } else sessionUsage();
        } catch (SessionStoreException e) {
            println("  [" + e.error() + "] " + e.getMessage());
        } catch (NumberFormatException e) {
            println("  Usage: /session prune <days>  (days must be > 0)");
        }
    }

    private void printSessions(List<SessionMeta> values, boolean showSummary) {
        if (values.isEmpty()) { println("  No saved sessions."); return; }
        System.out.println();
        for (SessionMeta s : values) {
            String updated = s.updatedAt().toString().replace("T", " ").substring(0, 16);
            println("  [" + s.id() + "] " + s.name() + "  (" + s.messageCount() + " msgs, " + updated + ")");
            String detail = showSummary ? s.summary() : s.firstUserMessage();
            if (detail != null && !detail.isBlank()) println("      " + abbreviate(detail, 80));
        }
        System.out.println();
    }

    private void printStats() {
        var buckets = sessions.stats();
        if (buckets.isEmpty()) { println("  No saved sessions."); return; }
        System.out.println();
        for (var bucket : buckets) {
            println("  " + pad(bucket.label(), 16) + String.format("%3d sessions %8s",
                bucket.count(), size(bucket.bytes())));
        }
        System.out.println();
    }

    private void sessionUsage() {
        println("  /session save [name] | load <id> | list | search <query> | stats | prune <days> | delete <id> | new");
    }

    private void skill(String args) {
        if (args.equals("list")) {
            var all = skills.listAll();
            if (all.isEmpty()) { println("  No skills found."); return; }
            System.out.println();
            all.forEach(s -> {
                println("  - " + s.name() + (engine.hasSkillLoaded(s.name()) ? " (*loaded)" : ""));
                println("    " + s.description());
            });
            System.out.println();
        } else if (args.startsWith("load ")) {
            String name = args.substring(5).trim();
            var result = engine.loadSkill(name);
            println(result.loaded() ? "  Skill loaded: " + name : "  [S-001] " + result.error() + ": " + name);
        } else if (args.startsWith("unload ")) {
            String name = args.substring(7).trim();
            println(engine.unloadSkill(name).unloaded() ? "  Skill unloaded: " + name : "  Skill was not loaded: " + name);
        } else println("  /skill list | load <name> | unload <name>");
    }

    private void mcp(String args) {
        if (mcp == null) { println("  MCP manager not initialized."); return; }
        if (args.isBlank()) {
            var status = mcp.status();
            if (status.isEmpty()) { println("  No MCP servers configured."); return; }
            System.out.println();
            status.forEach(s -> println("  " + ("RUNNING".equals(s.state()) ? "● " : "○ ")
                + pad(s.name(), 20) + pad(s.transport(), 8) + pad(s.state(), 10) + s.toolCount() + " tools"));
            System.out.println();
        } else if (args.startsWith("logs ")) {
            List<String> logs = mcp.logs(args.substring(5).trim());
            if (logs.isEmpty()) println("  No logs."); else logs.forEach(l -> println("  " + l));
        } else if (args.startsWith("disable ")) {
            String name = args.substring(8).trim(); mcp.disable(name); println("  MCP server '" + name + "' disabled.");
        } else if (args.startsWith("restart ") || args.startsWith("enable ")) {
            String name = args.substring(args.indexOf(' ') + 1).trim();
            List<Tool> tools = mcp.restart(name); tools.forEach(registry::register);
            println("  MCP server '" + name + "' started with " + tools.size() + " tools.");
        } else println("  /mcp | restart <name> | logs <name> | disable <name> | enable <name>");
    }

    private void runs() {
        try {
            var values = runs.listRuns(10);
            if (values.isEmpty()) { println("  暂无运行记录。"); return; }
            System.out.println();
            for (var r : values) println("  " + r.runId() + "  " + r.status() + "  turns=" + r.turns()
                + " tools=" + r.toolCalls() + " time=" + duration(r.durationMs()));
            System.out.println();
        } catch (Exception e) { println("  读取 run 记录失败: " + e.getMessage()); }
    }

    private void metrics(String runId) {
        try {
            if (runId.isBlank()) {
                var latest = runs.listRuns(1); if (latest.isEmpty()) { println("  暂无运行记录"); return; }
                runId = latest.getFirst().runId();
            }
            var m = runs.readMetrics(runId).value();
            if (m == null) { println("  run 记录不存在: " + runId); return; }
            var s = m.summary();
            println("  run: " + m.runId() + " status=" + s.status() + " duration=" + duration(s.durationMs())
                + " turns=" + s.turns() + " tools=" + s.toolCalls() + " failed=" + s.toolFailures());
        } catch (Exception e) { println("  读取 metrics 失败: " + e.getMessage()); }
    }

    private void trace(String runId) {
        try {
            if (runId.isBlank()) {
                var latest = runs.listRuns(1); if (latest.isEmpty()) { println("  暂无运行记录"); return; }
                runId = latest.getFirst().runId();
            }
            var result = runs.readEvents(runId);
            if (result.value().isEmpty()) { println("  trace 为空: " + runId); return; }
            result.warnings().forEach(w -> println("  ⚠ " + w.code() + " @line " + w.lineNumber() + ": " + w.message()));
            int shown = 0;
            for (var event : result.value()) {
                println(String.format("  %03d %-30s turn=%s", event.sequence(), event.eventType(), event.turnNumber()));
                if (++shown >= 20) break;
            }
        } catch (Exception e) { println("  读取 trace 失败: " + e.getMessage()); }
    }

    private static MemoryType parseType(String value, MemoryType fallback) {
        try { return MemoryType.valueOf(value.toUpperCase()); } catch (Exception e) { return fallback; }
    }
    private static String sanitize(String value) { return value.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase(); }
    private static String abbreviate(String value, int max) { return value.length() > max ? value.substring(0, max) + "..." : value; }
    private static String pad(String value, int width) { return String.format("%-" + width + "s", value); }
    private static String size(long bytes) { return bytes < 1024 ? bytes + " B" : String.format("%.1f KB", bytes / 1024.0); }
    private static String duration(long ms) { return ms < 1000 ? ms + "ms" : String.format("%.1fs", ms / 1000.0); }
    private static String gray(String text) { return ConsoleRenderer.GRAY + text + ConsoleRenderer.RESET; }
    private static void println(String text) { System.out.println(gray(text)); }
}
