package com.clawkit.tools.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP 审计日志。所有 mcp__ 开头的工具调用记录到 ~/.clawkit/logs/mcp-audit.log。
 * 使用 Jackson 序列化，每行合法 JSON。
 */
public class McpAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(McpAuditLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path auditLog;
    private final Object lock = new Object();

    public McpAuditLogger() {
        this(Path.of(System.getProperty("user.home"), ".clawkit", "logs", "mcp-audit.log"));
    }

    /** 可注入路径，方便测试 */
    public McpAuditLogger(Path auditLog) {
        this.auditLog = auditLog;
        try {
            Files.createDirectories(auditLog.getParent());
        } catch (IOException e) {
            log.warn("[MCP] failed to create audit log dir: {}", e.getMessage());
        }
    }

    public void logCall(String toolFullName, String arguments) {
        writeEntry(new McpAuditRecord(
            Instant.now(), toolFullName, "CALL", truncate(arguments, 300),
            null, null, null, 0, null));
    }

    public void logResult(String toolFullName, String outcome, int outputBytes) {
        writeEntry(new McpAuditRecord(
            Instant.now(), toolFullName, "RESULT", null,
            outcome, null, null, outputBytes, null));
    }

    private void writeEntry(McpAuditRecord record) {
        try {
            String line = MAPPER.writeValueAsString(record) + "\n";
            synchronized (lock) {
                try (BufferedWriter w = Files.newBufferedWriter(auditLog,
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(line);
                }
            }
        } catch (IOException e) {
            log.warn("[MCP] audit write failed: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /** 审计记录结构，Jackson 序列化为合法 JSON */
    record McpAuditRecord(
        Instant ts,
        String tool,
        String action,
        String argSummary,
        String outcome,
        String riskLevel,
        String approval,
        int outputBytes,
        String errorCode
    ) {}
}
