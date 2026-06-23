package com.miniclaw.tools.mcp;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MCP 审计日志。所有 mcp__ 开头的工具调用记录到 ~/.miniclaw/logs/mcp-audit.log */
public class McpAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(McpAuditLogger.class);
    private static final Path AUDIT_LOG =
        Path.of(System.getProperty("user.home"), ".miniclaw", "logs", "mcp-audit.log");
    private final Object lock = new Object();

    public McpAuditLogger() {
        try {
            Files.createDirectories(AUDIT_LOG.getParent());
        } catch (IOException e) {
            log.warn("[MCP] failed to create audit log dir: {}", e.getMessage());
        }
    }

    public void logCall(String toolFullName, String arguments) {
        writeEntry(toolFullName, "CALL", "args=" + truncate(arguments, 300));
    }

    public void logResult(String toolFullName, String outcome, int outputBytes) {
        writeEntry(toolFullName, "RESULT", outcome + " bytes=" + outputBytes);
    }

    private void writeEntry(String tool, String action, String detail) {
        String line = "{\"ts\":\"" + Instant.now() + "\",\"tool\":\""
            + tool + "\",\"action\":\"" + action + "\",\"" + detail.replace("\"", "'") + "\"}\n";
        synchronized (lock) {
            try (BufferedWriter w = Files.newBufferedWriter(AUDIT_LOG,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(line);
            } catch (IOException e) {
                log.warn("[MCP] audit write failed: {}", e.getMessage());
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
