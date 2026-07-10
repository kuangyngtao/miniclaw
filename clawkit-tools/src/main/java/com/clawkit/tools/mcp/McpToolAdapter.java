package com.clawkit.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawkit.tools.Result;
import com.clawkit.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MCP 工具 → clawkit Tool 接口适配。每个远程 MCP 工具对应一个 McpToolAdapter 实例。 */
public class McpToolAdapter implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final String toolName;
    private final String fullName;
    private final String description;
    private final String inputSchema;
    private final McpClient client;
    private final McpAuditLogger audit;

    public McpToolAdapter(String serverName, McpToolDef toolDef, McpClient client, String sanitizedSchema) {
        this.serverName = serverName;
        this.toolName = toolDef.name();
        this.fullName = "mcp__" + serverName + "__" + toolName;
        this.description = toolDef.description();
        this.inputSchema = sanitizedSchema;
        this.client = client;
        this.audit = new McpAuditLogger();
    }

    @Override
    public String name() { return fullName; }

    @Override
    public String description() {
        return "[MCP:" + serverName + "] " + description;
    }

    @Override
    public String inputSchema() { return inputSchema; }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public Result<String> execute(String arguments) {
        audit.logCall(fullName, arguments);
        try {
            JsonNode argsNode;
            try {
                argsNode = MAPPER.readTree(arguments);
            } catch (Exception e) {
                argsNode = MAPPER.createObjectNode();
            }
            String output = client.callTool(toolName, argsNode);
            audit.logResult(fullName, "SUCCESS", output.length());
            return new Result.Ok<>(output);
        } catch (Exception e) {
            audit.logResult(fullName, "FAILED: " + e.getMessage(), 0);
            log.warn("[MCP] {} failed: {}", fullName, e.getMessage());
            return new Result.Err<>(new Result.ErrorInfo("M-001",
                "[MCP:" + serverName + "] " + toolName + " — " + e.getMessage()));
        }
    }
}
