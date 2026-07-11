package com.clawkit.cli;

import com.clawkit.engine.*;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.observability.RunReader;

import java.util.List;

/**
 * 终端展示渲染器。所有 System.out 输出集中在此。
 * 只接收数据对象，不持有业务引用（engine/reader/service 不从字段取，从参数传）。
 */
public class ConsoleRenderer {

    // ── ANSI ──────────────────────────────────────────────────────

    public static final String GRAY = "\033[90m";
    public static final String GREEN = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String RED = "\033[31m";
    public static final String RESET = "\033[0m";

    static final String THINKING_BAR = "─".repeat(54);

    // ── Banner / Menu / Help ──────────────────────────────────────

    public void renderBanner(String model, int toolCount, String workDir) {
        System.out.println("  clawkit v0.1.0  " + model + "  " + toolCount + " tools  " + workDir);
        System.out.println();
    }

    public void renderMenu(ThinkingMode thinking, PermissionMode perm) {
        System.out.println();
        System.out.println(GRAY + "  /thinking   toggle thinking     /plan-exec plan+execute" + RESET);
        System.out.println(GRAY + "  /plan       read-only mode      (current: " + perm + ")" + RESET);
        System.out.println(GRAY + "  /ask        confirm writes      /auto   full-auto" + RESET);
        System.out.println(GRAY + "  /clear      reset session       /compact compress" + RESET);
        System.out.println(GRAY + "  /context    token usage         /remember add memory" + RESET);
        System.out.println(GRAY + "  /runs       recent runs         /metrics run summary" + RESET);
        System.out.println(GRAY + "  /trace      run event trace     /memory  list memories" + RESET);
        System.out.println(GRAY + "  /session    manage sessions     /skill load/unload" + RESET);
        System.out.println(GRAY + "  /mcp        MCP servers         /im-on /im-off /im-status" + RESET);
        System.out.println(GRAY + "  /help       show all commands   /exit  quit" + RESET);
        System.out.println();
    }

    public void renderHelp(ThinkingMode thinking, PermissionMode perm) {
        System.out.println();
        System.out.println("  clawkit v0.1.0 — your local AI coding companion");
        System.out.println();
        System.out.println("  COMMANDS");
        System.out.println("    /thinking    toggle slow thinking mode  (current: " + thinking + ")");
        System.out.println("    /plan        read-only tools only       (current: " + perm + ")");
        System.out.println("    /ask         confirm before each write tool");
        System.out.println("    /auto        execute all tools without confirmation");
        System.out.println("    /clear       reset conversation session");
        System.out.println("    /compact     manually compress context (L1/L2)");
        System.out.println("    /context     show token usage and session info");
        System.out.println("    /runs        list recent runs");
        System.out.println("    /metrics     show run summary [runId]");
        System.out.println("    /trace       show trace events <runId>");
        System.out.println("    /remember    add a memory entry (interactive)");
        System.out.println("    /memory      list or manage memory entries");
        System.out.println("    /session     save, load, list, search sessions");
        System.out.println("    /skill       list, load, unload skills");
        System.out.println("    /mcp         manage MCP servers (status/restart/logs/disable/enable)");
        System.out.println("    /im-on <id>  enable IM channel (feishu / weixin)");
        System.out.println("    /im-off [id] disable a channel or all");
        System.out.println("    /im-status   show all IM channel status");
        System.out.println("    /feishu-on   (alias for /im-on feishu)");
        System.out.println("    /feishu-off  (alias for /im-off feishu)");
        System.out.println("    /help        show this help");
        System.out.println("    /exit        quit clawkit");
        System.out.println();
        System.out.println("  PERMISSION MODES");
        System.out.println("    PLAN  — read-only tools only, generate implementation plans");
        System.out.println("    ASK   — confirm before each file write / command execution");
        System.out.println("    AUTO  — execute all tools without confirmation");
        System.out.println();
    }

    // ── Context / Runs / Metrics / Trace ──────────────────────────

    public void renderContext(AgentEngine engine) {
        int tokens = engine.getEstimatedTokens();
        int maxTokens = engine.getContextWindow();
        int pct = maxTokens > 0 ? tokens * 100 / maxTokens : 0;
        String bar = "░".repeat(10);
        int filled = Math.min(pct * 10 / 100, 10);
        if (filled > 0) bar = "█".repeat(filled) + "░".repeat(10 - filled);

        int msgCount = engine.getMessageCount();

        System.out.println();
        System.out.println(GRAY + "  tokens    [" + bar + "] " + tokens + " / " + maxTokens
            + " (" + pct + "%)" + RESET);
        System.out.println(GRAY + "  messages  " + msgCount + RESET);

        var report = engine.getContextBudgetReport();
        if (report != null) {
            String statusIcon = switch (report.status()) {
                case OK -> GREEN + "OK" + RESET;
                case WARN -> YELLOW + "WARN" + RESET;
                case COMPACT_REQUIRED -> RED + "COMPACT" + RESET;
                case HARD_LIMIT -> RED + "HARD_LIMIT" + RESET;
            };
            System.out.println(GRAY + "  budget    " + statusIcon + GRAY
                + "  total=" + report.totalTokens() + RESET);
            report.sections().forEach((section, t) -> {
                if (t > 0) System.out.println(GRAY + "    " + padRight(section.name(), 14)
                    + " " + t + " tokens" + RESET);
            });
        }

        var mask = engine.getLastMaskedContext();
        if (mask != null) {
            int evicted = mask.evictedTurnGroups() != null
                ? mask.evictedTurnGroups().size() : 0;
            System.out.println(GRAY + "  masked    T0=" + mask.tier0Count()
                + " T1=" + mask.tier1Count()
                + " T2=" + mask.tier2Count()
                + " T3=" + mask.tier3Count()
                + " evicted:" + evicted + RESET);
        }

        String compStatus = engine.getCompactionStatus();
        if (compStatus != null && !compStatus.isBlank()) {
            System.out.println(GRAY + "  compact   " + compStatus + RESET);
        }

        System.out.println(GRAY + "  mode      " + engine.thinkingMode()
            + " / " + engine.permissionMode() + RESET);
        System.out.println(GRAY + "  workdir   " + engine.workDir() + RESET);
        System.out.println();
    }

    public void renderRuns(RunReader runReader) {
        System.out.println();
        try {
            var runs = runReader.listRecent(10);
            if (runs.isEmpty()) {
                System.out.println(GRAY + "  暂无运行记录。执行一次任务后 run 记录会自动生成。" + RESET);
                System.out.println();
                return;
            }
            System.out.println(GRAY + "  run                                     status      turns  tools  fails  compact  time     mode" + RESET);
            System.out.println(GRAY + "  ──────────────────────────────────────── ─────────── ───── ────── ────── ──────── ──────── ──────" + RESET);
            for (var r : runs) {
                String time = r.startTime().length() > 16 ? r.startTime().substring(11, 16) : r.startTime();
                String duration = formatDuration(r.durationMs());
                System.out.printf("  %-40s %-11s %5d %6d %6d %8d %8s %s%n",
                    r.runId(), r.status(), r.turns(), r.toolCalls(),
                    r.toolFailures(), r.compactCount(), duration, r.permissionMode());
            }
            System.out.println();
        } catch (Exception e) {
            System.out.println(GRAY + "  读取 run 记录失败: " + e.getMessage() + RESET);
        }
    }

    public void renderMetrics(RunReader runReader, String runId) {
        System.out.println();
        try {
            com.clawkit.observability.model.RunMetrics m;
            if (runId != null) {
                m = runReader.readMetrics(runId);
            } else {
                var runs = runReader.listRecent(1);
                if (runs.isEmpty()) {
                    System.out.println(GRAY + "  暂无运行记录" + RESET);
                    System.out.println();
                    return;
                }
                m = runReader.readMetrics(runs.get(0).runId());
            }
            if (m == null) {
                System.out.println(GRAY + "  run 记录不存在: " + runId + RESET);
                System.out.println();
                return;
            }
            System.out.printf("  run:      %s%n", m.runId());
            System.out.printf("  status:   %s%n", m.status());
            System.out.printf("  duration: %s%n", formatDuration(m.durationMs()));
            System.out.printf("  turns:    %d%n", m.turns());
            System.out.printf("  tools:    %d (%d failed)%n", m.toolCalls(), m.toolFailures());
            System.out.printf("  compact:  %d%n", m.compactCount());
            System.out.printf("  mode:     %s / %s / %s%n", m.permissionMode(), m.thinkingMode(), m.executionMode());
            if (m.errorMessage() != null) {
                System.out.printf("  error:    %s%n", m.errorMessage());
            }
            System.out.println();
        } catch (Exception e) {
            System.out.println(GRAY + "  读取 metrics 失败: " + e.getMessage() + RESET);
        }
    }

    public void renderTrace(RunReader runReader, String runId) {
        System.out.println();
        try {
            if (runId == null) {
                var runs = runReader.listRecent(1);
                if (runs.isEmpty()) {
                    System.out.println(GRAY + "  暂无运行记录" + RESET);
                    System.out.println();
                    return;
                }
                runId = runs.get(0).runId();
            }
            var lines = runReader.readTrace(runId);
            if (lines.isEmpty()) {
                System.out.println(GRAY + "  trace 为空: " + runId + RESET);
                System.out.println();
                return;
            }
            System.out.println(GRAY + "  trace: " + runId + " (" + lines.size() + " events)" + RESET);
            int shown = 0;
            for (String line : lines) {
                if (line.contains("RunStarted") || line.contains("RunCompleted")
                    || line.contains("ToolCompleted") || line.contains("CompactCompleted")) {
                    String shortLine = line.length() > 120 ? line.substring(0, 117) + "..." : line;
                    System.out.println(GRAY + "  " + shortLine + RESET);
                    shown++;
                }
                if (shown >= 20) {
                    System.out.println(GRAY + "  ... (" + (lines.size() - shown) + " more events)" + RESET);
                    break;
                }
            }
            System.out.println();
        } catch (Exception e) {
            System.out.println(GRAY + "  读取 trace 失败: " + e.getMessage() + RESET);
        }
    }

    // ── Approval box ──────────────────────────────────────────────

    public void renderApprovalBox(ApprovalRequest req) {
        String riskIcon = switch (req.riskLevel()) {
            case LOW -> GREEN + "LOW" + RESET;
            case MEDIUM -> YELLOW + "MEDIUM" + RESET;
            case HIGH -> RED + "HIGH" + RESET;
        };
        String riskName = switch (req.riskLevel()) {
            case LOW -> "自动执行";
            case MEDIUM -> "需要确认";
            case HIGH -> "需要确认（高风险）";
        };
        System.out.println();
        System.out.println("  ┌─ " + YELLOW + "⚠ Approval Required" + RESET
            + " ─────────────────────────────┐");
        System.out.println("  │ tool:   " + req.toolName());
        System.out.println("  │ risk:   " + riskIcon + " — " + riskName);
        System.out.println("  │ params: " + req.parameters());
        if (req.llmIntent() != null && !req.llmIntent().isBlank()) {
            String intent = req.llmIntent();
            if (intent.length() > 80) intent = intent.substring(0, 77) + "...";
            System.out.println("  │ intent: " + GRAY + intent + RESET);
        }
        System.out.println("  └──────────────────────────────────────────────────────┘");
        System.out.print("  [y] approve  [a] approve all same  [n] reject  [m] modify → ");
    }

    // ── Thinking callbacks ────────────────────────────────────────

    public void onThinkingBegin() {
        System.out.println(GRAY + "  ┌" + THINKING_BAR + "┐" + RESET);
    }

    public void onThinkingToken(String token) {
        System.out.print(GRAY + token + RESET);
    }

    public void onReasoning(String text) {
        System.out.println(GRAY + "\n  └" + THINKING_BAR + "┘" + RESET);
    }

    // ── Tool event callbacks ──────────────────────────────────────

    public void onToolStart(String name, String argSummary) {
        String args = argSummary.isEmpty() ? "" : "  " + GRAY + argSummary + RESET;
        System.out.println("  [..] " + name + args);
    }

    public void onToolEnd(String name, boolean success, String detail) {
        String icon = success ? "OK" : "ER";
        String d = success ? detail : GRAY + detail + RESET;
        System.out.println("  [" + icon + "] " + name + "  " + d);
    }

    public void onSubAgentSpawn(String instruction, String type, int maxTurns) {
        String inst = instruction.length() > 100 ? instruction.substring(0, 100) + "..." : instruction;
        System.out.println(GRAY + "  [SubAgent] dispatching: \"" + inst + "\" ("
            + type + ", max " + maxTurns + " turns)" + RESET);
    }

    public void onSubAgentComplete(String summary, int turnsUsed, int tokens, long durationMs) {
        String s = summary.length() > 100 ? summary.substring(0, 100) + "..." : summary;
        String line = s.replace("\n", " ");
        System.out.println(GRAY + "  [SubAgent] done (" + turnsUsed
            + " turns, ~" + tokens + " tk, " + durationMs + "ms) → " + line + RESET);
    }

    // ── Static formatting utilities ──────────────────────────────

    public static String formatTokens(int tokens) {
        if (tokens < 1000) return tokens + "t";
        if (tokens < 10_000) return String.format("%.1fkt", tokens / 1000.0);
        return (tokens / 1000) + "kt";
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    public static String padRight(String s, int n) {
        if (s == null) s = "";
        return s.length() >= n ? s : s + " ".repeat(n - s.length());
    }

    public static String center(String s, int width) {
        if (s == null) s = "";
        int pad = (width - s.length()) / 2;
        return pad > 0 ? " ".repeat(pad) + s : s;
    }

    public static String boxLine(int width, String content) {
        return boxLine(width, content, '│', '│');
    }

    public static String boxLine(int width, String content, char left, char right) {
        if (content == null) content = "";
        int inner = width - 2;
        String padded = content.length() > inner
            ? content.substring(0, inner) : padRight(content, inner);
        return left + padded + right;
    }

    public static String truncatePath(String path, int maxLen) {
        if (path == null) return "";
        if (path.length() <= maxLen) return path;
        return "..." + path.substring(path.length() - maxLen + 3);
    }

    public static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        long min = ms / 60_000;
        long sec = (ms % 60_000) / 1000;
        return min + "m" + sec + "s";
    }
}
